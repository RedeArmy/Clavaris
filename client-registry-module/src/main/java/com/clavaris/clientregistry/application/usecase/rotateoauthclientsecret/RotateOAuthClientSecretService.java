package com.clavaris.clientregistry.application.usecase.rotateoauthclientsecret;

import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.ClientSecretHasher;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientSecretGenerator;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.common.application.port.AuditEventRecorder;

/**
 * Same rationale as {@code rotateorganizationclientsecret.RotateOrganizationClientSecretService} —
 * the real, code-driven way to rotate this credential, never bundled with deactivation. Reuses
 * {@link OAuthClientSecretGenerator}, the same port {@code
 * registeroauthclient.RegisterOAuthClientService} uses for its own initial secret.
 */
public class RotateOAuthClientSecretService implements RotateOAuthClientSecretUseCase {

  private final OAuthClientRepository oauthClients;
  private final ClientSecretHasher hasher;
  private final OAuthClientSecretGenerator secretGenerator;
  private final AuditEventRecorder auditEvents;

  public RotateOAuthClientSecretService(
      final OAuthClientRepository oauthClients,
      final ClientSecretHasher hasher,
      final OAuthClientSecretGenerator secretGenerator,
      final AuditEventRecorder auditEvents) {
    this.oauthClients = oauthClients;
    this.hasher = hasher;
    this.secretGenerator = secretGenerator;
    this.auditEvents = auditEvents;
  }

  @Override
  public RotateOAuthClientSecretResult handle(final RotateOAuthClientSecretCommand command) {
    final OAuthClient existing =
        oauthClients
            .findByClientId(command.clientId())
            .orElseThrow(() -> new OAuthClientNotFoundException(command.clientId()));

    final String rawSecret = secretGenerator.generate();
    final OAuthClient rotated = existing.rotateSecret(hasher.hash(rawSecret));
    oauthClients.save(rotated);

    auditEvents.write(
        command.actor(),
        "oauth_client.secret_rotated",
        "Organization",
        existing.organizationId().toString(),
        "clientId=" + command.clientId());

    return new RotateOAuthClientSecretResult(command.clientId(), rawSecret);
  }
}
