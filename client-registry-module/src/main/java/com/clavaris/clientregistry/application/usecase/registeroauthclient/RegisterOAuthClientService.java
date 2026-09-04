package com.clavaris.clientregistry.application.usecase.registeroauthclient;

import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.ClientSecretHasher;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * Orchestration for {@link RegisterOAuthClientUseCase}. Generates both {@code clientId} and the raw
 * secret server-side, never accepts either from the caller — a machine credential is stronger
 * generated here than accepted from an operator's own (potentially weak or reused) choice.
 *
 * <p><b>SDE-III feature build, 2026-09-04 (Clerk Development/Production instances analysis):</b>
 * {@code clientId} is now prefixed {@code test_}/{@code live_} depending on the owning
 * Organization's own environment — same mechanic as Clerk's own {@code pk_test_}/{@code pk_live_}
 * key prefixes: the credential itself, not just a side-channel dashboard label, tells you which
 * environment it belongs to. Purely a visual/structural convention, same as Clerk's own — nothing
 * in {@code OrganizationRegisteredClientRepository}'s own lookup logic branches on this prefix; the
 * real isolation is Organization-scoped issuer/JWKS (ADR-0010 §5), not the prefix string.
 */
@SuppressWarnings("PMD.LongVariable")
public class RegisterOAuthClientService implements RegisterOAuthClientUseCase {

  // 256 bits — same order of magnitude as the RSA-2048 signing keys this credential ultimately
  // guards access to; Base64url encoding keeps the raw secret transport/display-safe without an
  // extra encoding step at the controller.
  private static final int SECRET_LENGTH = 32;

  private static final String DEVELOPMENT_CLIENT_ID_PREFIX = "test_";
  private static final String PRODUCTION_CLIENT_ID_PREFIX = "live_";

  private final OAuthClientRepository oauthClients;
  private final OrganizationExistsChecker orgExistsChecker;

  @SuppressWarnings("PMD.LongVariable")
  private final OrganizationEnvironmentChecker environmentChecker;

  private final ClientSecretHasher hasher;
  private final SecureRandom secureRandom = new SecureRandom();

  public RegisterOAuthClientService(
      final OAuthClientRepository oauthClients,
      final OrganizationExistsChecker orgExistsChecker,
      @SuppressWarnings("PMD.LongVariable") final OrganizationEnvironmentChecker environmentChecker,
      final ClientSecretHasher hasher) {
    this.oauthClients = oauthClients;
    this.orgExistsChecker = orgExistsChecker;
    this.environmentChecker = environmentChecker;
    this.hasher = hasher;
  }

  @Override
  public RegisterOAuthClientResult handle(final RegisterOAuthClientCommand command) {
    // BR-ORG-02: never let a client be registered under a non-existent Organization — the FK
    // constraint doesn't enforce this. Cross-module migration ordering isn't guaranteed (see the
    // signing_keys migration's own comment for the same reasoning), so the application layer is
    // the only thing that actually rules this out.
    if (!orgExistsChecker.exists(command.organizationId())) {
      throw new OrganizationNotFoundException(command.organizationId());
    }

    final String clientIdPrefix =
        environmentChecker.isDevelopment(command.organizationId())
            ? DEVELOPMENT_CLIENT_ID_PREFIX
            : PRODUCTION_CLIENT_ID_PREFIX;
    final String clientId = clientIdPrefix + UUID.randomUUID();
    final String rawClientSecret = generateRawSecret();
    final OAuthClient client =
        OAuthClient.register(
            command.organizationId(),
            clientId,
            hasher.hash(rawClientSecret),
            command.redirectUris(),
            command.allowedGrantTypes(),
            command.allowedScopes(),
            command.requireConsent(),
            command.postLogoutRedirectUris());

    oauthClients.save(client);
    return new RegisterOAuthClientResult(client, rawClientSecret);
  }

  private String generateRawSecret() {
    final byte[] bytes = new byte[SECRET_LENGTH];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
