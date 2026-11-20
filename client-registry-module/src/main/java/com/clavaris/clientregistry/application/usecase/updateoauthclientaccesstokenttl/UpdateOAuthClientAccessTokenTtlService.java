package com.clavaris.clientregistry.application.usecase.updateoauthclientaccesstokenttl;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.common.application.port.AuditEventRecorder;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * TD-SEC-104 / ADR-0049: changes how long an access token issued to one client lives. The domain
 * enforces the bounds (60 to 3600 seconds) and refuses anything else as an {@code
 * InvalidInputException}; a null returns the client to the default. Both directions are audited
 * with what it was and what it became, because a longer lifetime widens the window in which a
 * revoked token still works.
 */
public class UpdateOAuthClientAccessTokenTtlService
    implements UpdateOAuthClientAccessTokenTtlUseCase {

  private final OAuthClientRepository oauthClients;
  private final AuditEventRecorder auditEvents;

  public UpdateOAuthClientAccessTokenTtlService(
      final OAuthClientRepository oauthClients, final AuditEventRecorder auditEvents) {
    this.oauthClients = oauthClients;
    this.auditEvents = auditEvents;
  }

  @Override
  @Transactional
  public void handle(final UpdateOAuthClientAccessTokenTtlCommand command) {
    final OAuthClient existing =
        oauthClients
            .findByClientId(command.clientId())
            .filter(found -> found.organizationId().equals(command.organizationId()))
            .orElseThrow(() -> new OAuthClientNotFoundException(command.clientId()));

    if (!existing.active()) {
      throw new OAuthClientInactiveException(command.clientId());
    }
    if (Objects.equals(
        existing.accessTokenTtlSeconds().orElse(null), command.accessTokenTtlSeconds())) {
      return;
    }

    final OAuthClient updated = existing.updateAccessTokenTtl(command.accessTokenTtlSeconds());
    oauthClients.save(updated);

    auditEvents.write(
        command.actor(),
        "oauth_client.access_token_ttl_updated",
        "Organization",
        existing.organizationId().toString(),
        "clientId="
            + command.clientId()
            + " from="
            + existing.effectiveAccessTokenTtlSeconds()
            + " to="
            + updated.effectiveAccessTokenTtlSeconds());
  }
}
