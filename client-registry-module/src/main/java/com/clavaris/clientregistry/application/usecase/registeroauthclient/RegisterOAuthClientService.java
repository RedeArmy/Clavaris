package com.clavaris.clientregistry.application.usecase.registeroauthclient;

import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.ClientSecretHasher;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.common.application.port.AuditEventRecorder;
import java.util.UUID;

/**
 * Orchestration for {@link RegisterOAuthClientUseCase}. Generates both {@code clientId} and the raw
 * secret server-side, never accepts either from the caller — a machine credential is stronger
 * generated here than accepted from an operator's own (potentially weak or reused) choice.
 *
 * <p><b>SDE-III correction, 2026-09-24 (dashboard UX finding — live "API Keys" page review):</b>
 * {@code clientId} was previously prefixed {@code test_}/{@code live_} depending on the owning
 * Organization's own environment (2026-09-04 change, now superseded). In practice this collided
 * visually with the Organization's own {@code pk_test_}/{@code pk_live_} publishable key
 * (organization-module's {@code OrganizationApiKeys}) — two differently-scoped identifiers (one
 * per-Organization, one per-OAuthClient) that looked like the same family and were easy to
 * transpose by mistake. Corrected to a fixed {@code client_} prefix with no environment encoded,
 * matching Stripe's own actual convention: environment lives on the credential PAIR ({@code
 * pk_}/{@code sk_}), never on a plain resource id — {@link
 * com.clavaris.clientregistry.application.usecase.createorganizationclient.CreateOrganizationClientService}'s
 * {@code sk_test_}/{@code sk_live_} secret-key prefix is the correct half of that pair and is
 * deliberately left unchanged by this correction. Purely a visual/structural convention, same as
 * before — nothing in {@code OrganizationRegisteredClientRepository}'s own lookup logic ever
 * branched on this prefix; the real isolation is Organization-scoped issuer/JWKS (ADR-0010 §5), not
 * the prefix string, so removing the environment encoding changes no security-relevant behaviour.
 *
 * <p>SDE-III review, 2026-09-11: raw-secret generation moved to {@link OAuthClientSecretGenerator}
 * (was this class's own private {@code SecureRandom} logic) so {@code
 * rotateoauthclientsecret.RotateOAuthClientSecretService} can reuse it — same "one small port per
 * credential type, shared between create and rotate" shape {@code
 * OrganizationClientSecretGenerator} already established for the sibling credential type.
 */
public class RegisterOAuthClientService implements RegisterOAuthClientUseCase {

  private static final String CLIENT_ID_PREFIX = "client_";

  private final OAuthClientRepository oauthClients;
  private final OrganizationExistsChecker orgExistsChecker;
  private final ClientSecretHasher hasher;
  private final OAuthClientSecretGenerator secretGenerator;
  private final AuditEventRecorder auditEvents;

  public RegisterOAuthClientService(
      final OAuthClientRepository oauthClients,
      final OrganizationExistsChecker orgExistsChecker,
      final ClientSecretHasher hasher,
      final OAuthClientSecretGenerator secretGenerator,
      final AuditEventRecorder auditEvents) {
    this.oauthClients = oauthClients;
    this.orgExistsChecker = orgExistsChecker;
    this.hasher = hasher;
    this.secretGenerator = secretGenerator;
    this.auditEvents = auditEvents;
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

    final String clientId = CLIENT_ID_PREFIX + UUID.randomUUID();
    final String rawClientSecret = secretGenerator.generate();
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

    // Never the raw secret, never the hash — same BR-DATA-01 discipline as every other audited
    // secret-bearing action in this codebase (e.g. CreateOrganizationClientService).
    auditEvents.write(
        command.actor(),
        "oauth_client.registered",
        "Organization",
        command.organizationId().toString(),
        "clientId=" + clientId);
    return new RegisterOAuthClientResult(client, rawClientSecret);
  }
}
