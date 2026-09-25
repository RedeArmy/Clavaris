package com.clavaris.clientregistry.application.usecase.activateoauthclient;

import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.ClientSecretHasher;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientSecretGenerator;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.common.application.port.AuditEventRecorder;

/**
 * Live UX request, 2026-09-25: reactivation now also rotates the client secret — see {@link
 * ActivateOAuthClientResult}'s own Javadoc for why. Reuses {@link OAuthClientSecretGenerator}, the
 * same port {@code registeroauthclient.RegisterOAuthClientService}/{@code
 * rotateoauthclientsecret.RotateOAuthClientSecretService} already use for theirs. Writes two audit
 * events, not one — {@code oauth_client.activated} and {@code oauth_client.secret_rotated} — same
 * granularity a caller would see from two separate actions, since this genuinely performs both.
 */
public class ActivateOAuthClientService implements ActivateOAuthClientUseCase {

  private final OAuthClientRepository oauthClients;
  private final ClientSecretHasher hasher;
  private final OAuthClientSecretGenerator secretGenerator;
  private final AuditEventRecorder auditEvents;

  public ActivateOAuthClientService(
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
  public ActivateOAuthClientResult handle(final ActivateOAuthClientCommand command) {
    final OAuthClient existing =
        oauthClients
            .findByClientId(command.clientId())
            // Same cross-tenant-mismatch-collapses-to-404 reasoning as
            // DeactivateOAuthClientService's own identical check.
            .filter(found -> found.organizationId().equals(command.organizationId()))
            .orElseThrow(() -> new OAuthClientNotFoundException(command.clientId()));

    final String rawSecret = secretGenerator.generate();
    final OAuthClient reactivated = existing.activate().rotateSecret(hasher.hash(rawSecret));
    oauthClients.save(reactivated);

    auditEvents.write(
        command.actor(),
        "oauth_client.activated",
        "Organization",
        existing.organizationId().toString(),
        "clientId=" + command.clientId());
    auditEvents.write(
        command.actor(),
        "oauth_client.secret_rotated",
        "Organization",
        existing.organizationId().toString(),
        "clientId=" + command.clientId());

    return new ActivateOAuthClientResult(command.clientId(), rawSecret);
  }
}
