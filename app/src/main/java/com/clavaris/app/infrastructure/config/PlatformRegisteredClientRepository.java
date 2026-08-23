package com.clavaris.app.infrastructure.config;

import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.PlatformClientRepository;
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
 */
@Repository
class PlatformRegisteredClientRepository implements RegisteredClientRepository {

  private final PlatformClientRepository platformClients;

  /* package */ PlatformRegisteredClientRepository(final PlatformClientRepository platformClients) {
    this.platformClients = platformClients;
  }

  @Override
  public void save(final RegisteredClient registeredClient) {
    // BR-PLATFORM-03: the only path that ever creates a PlatformClient is the bootstrap runner
    // (client-registry-module), which writes through PlatformClientRepository directly — Spring
    // Authorization Server itself never needs to persist a new client through this SPI method for
    // the client_credentials-only flow this issuer supports.
    throw new UnsupportedOperationException(
        "PlatformClient creation goes through BootstrapPlatformClientUseCase (BR-PLATFORM-03), never through this SPI");
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
    // row (e.g. to process /oauth2/revoke) — filtering it out for a now-revoked PlatformClient
    // would break the ability to explicitly revoke that same client's own lingering tokens during
    // an incident, the opposite of what revocation is for.
    try {
      return platformClients
          .findById(UUID.fromString(id))
          .map(this::toRegisteredClient)
          .orElse(null);
    } catch (final IllegalArgumentException _) {
      return null;
    }
  }

  // TD-SEC-018: an inactive (revoked) PlatformClient must resolve to "not found," the SPI's own
  // convention for "can't authenticate this" — same treatment findById above already gives one.
  // This is what actually enforces DeactivatePlatformClientService's own consequence: the very
  // next client_credentials attempt against a revoked client fails here, before Argon2 secret
  // verification even runs.
  @Override
  public RegisteredClient findByClientId(final String clientId) {
    return platformClients
        .findByClientId(clientId)
        .filter(PlatformClient::active)
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
}
