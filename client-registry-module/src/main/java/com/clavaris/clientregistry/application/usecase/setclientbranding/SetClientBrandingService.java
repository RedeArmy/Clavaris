package com.clavaris.clientregistry.application.usecase.setclientbranding;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.ClientBranding;
import com.clavaris.common.application.port.AuditEventRecorder;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-0009 §3. Operator-managed only in v1, same posture as every other per-client policy surface
 * (see {@code SetRedirectPolicyForClientService}'s own identical rationale).
 */
public class SetClientBrandingService implements SetClientBrandingUseCase {

  private final OAuthClientRepository oauthClients;
  private final ClientBrandingRepository brandings;
  private final AuditEventRecorder auditEvents;

  public SetClientBrandingService(
      final OAuthClientRepository oauthClients,
      final ClientBrandingRepository brandings,
      final AuditEventRecorder auditEvents) {
    this.oauthClients = oauthClients;
    this.brandings = brandings;
    this.auditEvents = auditEvents;
  }

  @Override
  @Transactional
  public SetClientBrandingResult handle(final SetClientBrandingCommand command) {
    oauthClients
        .findById(command.oauthClientId())
        .filter(found -> found.organizationId().equals(command.organizationId()))
        .orElseThrow(() -> new OAuthClientNotFoundException(command.oauthClientId()));

    final ClientBranding branding =
        brandings
            .findByOAuthClientId(command.oauthClientId())
            .map(
                existing ->
                    existing.withBranding(
                        command.logoUrl(),
                        command.primaryColor(),
                        command.applicationDisplayName()))
            .orElseGet(
                () ->
                    ClientBranding.define(
                        command.oauthClientId(),
                        command.logoUrl(),
                        command.primaryColor(),
                        command.applicationDisplayName()));

    brandings.save(branding);

    auditEvents.write(
        command.actor(),
        "client_branding.set",
        "OAuthClient",
        command.oauthClientId().toString(),
        "organizationId=" + command.organizationId());

    return new SetClientBrandingResult(branding);
  }
}
