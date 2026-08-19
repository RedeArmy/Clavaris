package com.clavaris.app.infrastructure.config;

import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.PlatformClientRepository;
import com.clavaris.clientregistry.domain.model.PlatformClient;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Component;

/**
 * Adapts client-registry-module's {@link PlatformClientRepository} port to Spring Authorization
 * Server's own {@link RegisteredClientRepository} SPI — the bridge lives in {@code app}, not either
 * business module, for the same reason {@code SigningKeyProvisioner}'s implementation does (see
 * {@code CreateOrganizationSigningKeyBridge}): app is the one module allowed to depend on both.
 */
@Component
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
  @SuppressWarnings("PMD.ShortVariable")
  @Override
  public RegisteredClient findById(final String id) {
    // Not exercised by the client_credentials grant (the only one this issuer supports, per the
    // spike's own scope) — clients authenticate with Basic-Auth clientId/secret, resolved via
    // findByClientId below, not by this internal id lookup.
    throw new UnsupportedOperationException(
        "Not needed by the client_credentials-only flow this issuer supports");
  }

  @Override
  public RegisteredClient findByClientId(final String clientId) {
    return platformClients.findByClientId(clientId).map(this::toRegisteredClient).orElse(null);
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
