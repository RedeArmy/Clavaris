package com.clavaris.app.infrastructure.config;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

/**
 * Adapts client-registry-module's {@link OAuthClientRepository} to Spring Authorization Server's
 * {@link RegisteredClientRepository} SPI for the dynamic per-Organization issuer — see spike 0001's
 * Appendix C addendum for why this is a single shared instance rather than one repository per
 * tenant (SAS 7.1.0's {@code multipleIssuersAllowed} runs one chain for every Organization, not one
 * chain per Organization).
 *
 * <p><b>Cross-tenant isolation is enforced here, explicitly, not structurally.</b> {@code
 * OAuthClientRepository.findByClientId} resolves purely by the globally-unique {@code clientId},
 * with no awareness of which Organization's endpoint the request actually hit. A client registered
 * under one Organization presenting valid credentials at a *different* Organization's token
 * endpoint must still be rejected (ADR-0010 §5's "structurally impossible, not policy-disallowed"
 * bar) — done here by comparing the resolved client's own {@code organizationId} against {@link
 * CurrentOrganizationContext}'s and treating a mismatch identically to "client not found". Covered
 * by a dedicated live cross-tenant rejection test, not just a happy-path one, per the addendum's
 * own call-out of this residual risk.
 */
final class OrganizationRegisteredClientRepository implements RegisteredClientRepository {

  private final OAuthClientRepository oauthClients;

  /* package */ OrganizationRegisteredClientRepository(final OAuthClientRepository oauthClients) {
    this.oauthClients = oauthClients;
  }

  @Override
  public void save(final RegisteredClient registeredClient) {
    // BR-CLIENT registration goes through RegisterOAuthClientUseCase (client-registry-module),
    // never through this SPI method — same rationale as the platform tier's own
    // PlatformRegisteredClientRepository.
    throw new UnsupportedOperationException(
        "OAuthClient creation goes through RegisterOAuthClientUseCase, never through this SPI");
  }

  // Parameter name matches RegisteredClientRepository's own interface signature — kept as-is for
  // readability against the SPI it implements, same precedent as
  // PlatformRegisteredClientRepository.
  @SuppressWarnings({"PMD.ShortVariable", "PMD.OnlyOneReturn"})
  @Override
  public RegisteredClient findById(final String id) {
    // TD-SEC-010 (closed): JdbcOAuth2AuthorizationService (TD-SEC-003) calls this on every reload
    // of a persisted OAuth2Authorization row for the interactive Authorization Code flow — no
    // longer unreachable now that authorization state actually persists. Same cross-tenant
    // isolation discipline as findByClientId below: a malformed id or a real client belonging to a
    // *different* Organization than the current request both resolve to null, identically to
    // "not found" — never a raw exception, and never a cross-tenant client handed back.
    final Optional<UUID> orgId = CurrentOrganizationContext.currentOrganizationId();
    if (orgId.isEmpty()) {
      return null;
    }
    final UUID clientId;
    try {
      clientId = UUID.fromString(id);
    } catch (final IllegalArgumentException _) {
      return null;
    }
    return oauthClients
        .findById(clientId)
        .filter(client -> client.organizationId().equals(orgId.get()))
        .map(OrganizationRegisteredClientRepository::toRegisteredClient)
        .orElse(null);
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @Override
  public RegisteredClient findByClientId(final String clientId) {
    final Optional<UUID> orgId = CurrentOrganizationContext.currentOrganizationId();
    if (orgId.isEmpty()) {
      return null;
    }
    return oauthClients
        .findByClientId(clientId)
        .filter(client -> client.organizationId().equals(orgId.get()))
        .map(OrganizationRegisteredClientRepository::toRegisteredClient)
        .orElse(null);
  }

  private static RegisteredClient toRegisteredClient(final OAuthClient client) {
    final RegisteredClient.Builder builder =
        RegisteredClient.withId(client.id().toString())
            .clientId(client.clientId())
            .clientSecret(client.clientSecretHash())
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            // BR-CLIENT-03: PKCE is mandatory for every OAuthClient, confidential or not — enforced
            // here at SAS-config level rather than stored per-client in the domain (this is that
            // task, per OAuthClient's own design notes).
            .clientSettings(ClientSettings.builder().requireProofKey(true).build());
    client
        .allowedGrantTypes()
        .forEach(grant -> builder.authorizationGrantType(new AuthorizationGrantType(grant)));
    client.allowedScopes().forEach(builder::scope);
    client.redirectUris().forEach(builder::redirectUri);
    return builder.build();
  }
}
