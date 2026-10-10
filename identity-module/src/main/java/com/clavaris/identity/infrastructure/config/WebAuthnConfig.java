package com.clavaris.identity.infrastructure.config;

import com.clavaris.identity.application.usecase.registerwebauthncredential.RelyingPartyFactory;
import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import java.net.URI;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * TD-FUT-034, Clerk "View Profile" passkeys parity — builds the single, app-wide {@link
 * RelyingParty} every Organization's WebAuthn ceremonies share.
 *
 * <p><b>One shared RP, not one per Organization (deliberate, not an oversight):</b> Clavaris's
 * multi-tenancy is path-based ({@code /o/{organizationId}/...}) — every tenant's hosted login
 * shares one browser origin, and WebAuthn ties a credential to exactly one origin/RP ID regardless
 * of path. A credential is still scoped to exactly one {@link
 * com.clavaris.identity.domain.model.Account} via the {@code webauthn_credentials.account_id} FK,
 * and {@code AccountId} values are globally unique across every Organization — sharing one RP ID
 * introduces no cross-tenant leakage, the same way one shared Postgres instance hosting every
 * Organization's {@code accounts} rows doesn't either. This is a different isolation axis than
 * JWKS/signing keys, which genuinely are per-Organization (ADR-0010 §5) because a leaked signing
 * key compromises every token under it; a WebAuthn credential is meaningless without the specific
 * {@code Account} row it's bound to, so there's nothing a shared RP ID could leak between tenants.
 */
// PMD.AtLeastOneConstructor: a @Configuration class with a single @Bean factory method and no
// fields — Spring never calls a constructor on it directly, same precedent every other
// constructor-less *Config class in this package already establishes. PMD.LongVariable:
// RELYING_PARTY_NAME/credentialRepository name exactly what they are.
@SuppressWarnings({"PMD.AtLeastOneConstructor", "PMD.LongVariable"})
@Configuration
class WebAuthnConfig {

  private static final String RELYING_PARTY_NAME = "Clavaris";

  // Used to authenticate with a passkey. The name is never shown in that ceremony; it is only
  // shown when a passkey is created, and that goes through relyingPartyFactory below.
  @Bean
  /* package */ RelyingParty relyingParty(
      @Value("${CLAVARIS_BASE_URL:http://localhost:8080}") final String clavarisBaseUrl,
      final CredentialRepository credentialRepository) {
    return build(clavarisBaseUrl, credentialRepository, RELYING_PARTY_NAME);
  }

  // The relying party a passkey is created under, named per Organization: the browser shows the
  // name when it asks to save the passkey, and for a consuming application's Account that must be
  // the application's, not Clavaris's. The id and origins are the deployment's own either way: they
  // are what the passkey is bound to and what the browser enforces. With no name it shows the host.
  @Bean
  /* package */ RelyingPartyFactory relyingPartyFactory(
      @Value("${CLAVARIS_BASE_URL:http://localhost:8080}") final String clavarisBaseUrl,
      final CredentialRepository credentialRepository) {
    return displayName ->
        build(
            clavarisBaseUrl,
            credentialRepository,
            displayName == null || displayName.isBlank()
                ? URI.create(clavarisBaseUrl).getHost()
                : displayName.strip());
  }

  private static RelyingParty build(
      final String clavarisBaseUrl,
      final CredentialRepository credentialRepository,
      final String name) {
    final URI baseUri = URI.create(clavarisBaseUrl);
    final RelyingPartyIdentity identity =
        RelyingPartyIdentity.builder().id(baseUri.getHost()).name(name).build();
    return RelyingParty.builder()
        .identity(identity)
        .credentialRepository(credentialRepository)
        .origins(Set.of(clavarisBaseUrl))
        .build();
  }
}
