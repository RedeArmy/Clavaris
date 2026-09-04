package com.clavaris.app.infrastructure.config;

import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.PlatformClientRepository;
import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientRepository;
import com.clavaris.clientregistry.domain.model.OrganizationClient;
import com.clavaris.clientregistry.domain.model.PlatformClient;
import java.util.UUID;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Repository;

/**
 * Adapts client-registry-module's {@link PlatformClientRepository} port to Spring Authorization
 * Server's own {@link RegisteredClientRepository} SPI — the bridge lives in {@code app}, not either
 * business module, for the same reason {@code SigningKeyProvisioner}'s implementation does (see
 * {@code CreateOrganizationSigningKeyBridge}): app is the one module allowed to depend on both.
 * {@code @Repository}, not {@code @Component}: this class genuinely is a {@code *Repository}
 * implementation (Spring's own naming convention), and the stereotype also enables JPA/Hibernate
 * exception translation for whatever {@code platformClients} does underneath.
 *
 * <p>ADR-0023: also resolves an {@code OrganizationClient} (Secret Key) by the exact same {@code
 * client_credentials} flow — one shared {@code /oauth2/token} endpoint issuing either a fully
 * unscoped {@code PlatformClient} token or an Organization-bound one, differentiated only by which
 * credential is presented (matches Clerk's own single Backend API URL, reached with either a test
 * or live secret key). {@code client_id} namespaces never collide — {@code OrganizationClient}'s
 * own {@code sk_test_}/{@code sk_live_} prefix vs. {@code PlatformClient}'s unprefixed ids — so
 * trying {@code PlatformClient} first, then falling back to {@code OrganizationClient}, is
 * unambiguous.
 */
@SuppressWarnings("PMD.LongVariable")
@Repository
class PlatformRegisteredClientRepository implements RegisteredClientRepository {

  private final PlatformClientRepository platformClients;
  private final OrganizationClientRepository organizationClients;

  /* package */ PlatformRegisteredClientRepository(
      final PlatformClientRepository platformClients,
      final OrganizationClientRepository organizationClients) {
    this.platformClients = platformClients;
    this.organizationClients = organizationClients;
  }

  @Override
  public void save(final RegisteredClient registeredClient) {
    // BR-PLATFORM-03/ADR-0023: neither PlatformClient (bootstrap runner only) nor
    // OrganizationClient
    // (CreateOrganizationClientUseCase only) is ever created through this SPI method.
    throw new UnsupportedOperationException(
        "PlatformClient/OrganizationClient creation never goes through this SPI (BR-PLATFORM-03, ADR-0023)");
  }

  // Parameter name matches RegisteredClientRepository's own interface signature (findById(String
  // id)) — kept as-is for readability against the SPI it implements, rather than renamed just to
  // dodge PMD's ShortVariable rule.
  @SuppressWarnings({"PMD.ShortVariable", "PMD.OnlyOneReturn"})
  @Override
  public RegisteredClient findById(final String id) {
    // TD-SEC-010 (closed): JdbcOAuth2AuthorizationService (TD-SEC-003) calls this on every reload
    // of a persisted OAuth2Authorization row — no longer unreachable now that authorization state
    // actually persists. The SPI's own null-for-not-found convention (mirrored by findByClientId
    // below) extends to a malformed id, same as "unknown client" — never an exception for a
    // not-found/malformed lookup, only for genuinely unsupported operations (save() above).
    // Deliberately does NOT filter by active() here, unlike findByClientId below: this overload
    // is what JdbcOAuth2AuthorizationService uses to reconstruct an ALREADY-ISSUED authorization
    // row (e.g. to process /oauth2/revoke) — filtering it out for a now-revoked client would break
    // the ability to explicitly revoke that same client's own lingering tokens during an incident,
    // the opposite of what revocation is for.
    try {
      final UUID uuid = UUID.fromString(id);
      final RegisteredClient platformMatch =
          platformClients.findById(uuid).map(this::toRegisteredClient).orElse(null);
      if (platformMatch != null) {
        return platformMatch;
      }
      return organizationClients.findById(uuid).map(this::toRegisteredClient).orElse(null);
    } catch (final IllegalArgumentException _) {
      return null;
    }
  }

  // TD-SEC-018/ADR-0023: an inactive (revoked) client must resolve to "not found," the SPI's own
  // convention for "can't authenticate this" — same treatment findById above already gives one.
  // This is what actually enforces Deactivate*ClientService's own consequence: the very next
  // client_credentials attempt against a revoked client fails here, before Argon2 secret
  // verification even runs.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Override
  public RegisteredClient findByClientId(final String clientId) {
    final RegisteredClient platformMatch =
        platformClients
            .findByClientId(clientId)
            .filter(PlatformClient::active)
            .map(this::toRegisteredClient)
            .orElse(null);
    if (platformMatch != null) {
      return platformMatch;
    }
    return organizationClients
        .findByClientId(clientId)
        .filter(OrganizationClient::active)
        .map(this::toRegisteredClient)
        .orElse(null);
  }

  private RegisteredClient toRegisteredClient(final PlatformClient platformClient) {
    final RegisteredClient.Builder builder =
        RegisteredClient.withId(platformClient.id().toString())
            .clientId(platformClient.clientId())
            .clientSecret(platformClient.clientSecretHash())
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS);
    platformClient.allowedScopes().forEach(builder::scope);
    return builder.build();
  }

  private RegisteredClient toRegisteredClient(final OrganizationClient organizationClient) {
    final RegisteredClient.Builder builder =
        RegisteredClient.withId(organizationClient.id().toString())
            .clientId(organizationClient.clientId())
            .clientSecret(organizationClient.clientSecretHash())
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS);
    organizationClient.allowedScopes().forEach(builder::scope);
    return builder.build();
  }
}
