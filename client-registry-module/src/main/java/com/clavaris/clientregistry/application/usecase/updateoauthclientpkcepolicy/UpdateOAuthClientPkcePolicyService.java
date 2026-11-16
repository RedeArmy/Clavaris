package com.clavaris.clientregistry.application.usecase.updateoauthclientpkcepolicy;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.clientregistry.domain.model.OptionalPkceNotAllowedException;
import com.clavaris.clientregistry.domain.model.PkcePolicy;
import com.clavaris.common.application.port.AuditEventRecorder;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-0043: changes whether a client must send a PKCE challenge. Same shape as {@code
 * updateoauthclientconsent.UpdateOAuthClientConsentService}, with two differences that come from
 * what this setting is:
 *
 * <ul>
 *   <li>the audit event says what the policy was and what it became, not just that it changed. The
 *       row is a security setting (it can take a requirement away), and a reader of the log has to
 *       be able to tell a client that was relaxed from one that was tightened without opening the
 *       client;
 *   <li>asking for the policy a client already has is a no-op and writes no event, so a form posted
 *       twice, or a script that sets the value it wants on every run, does not fill the log with
 *       changes that changed nothing.
 * </ul>
 *
 * Whether OPTIONAL is allowed for this client at all is the domain's rule ({@code
 * PkcePolicy#requireAllowedFor}), enforced when the new instance is built; this service does not
 * repeat it.
 */
public class UpdateOAuthClientPkcePolicyService implements UpdateOAuthClientPkcePolicyUseCase {

  private final OAuthClientRepository oauthClients;
  private final AuditEventRecorder auditEvents;
  private final boolean allowOptionalPkce;

  public UpdateOAuthClientPkcePolicyService(
      final OAuthClientRepository oauthClients,
      final AuditEventRecorder auditEvents,
      final boolean allowOptionalPkce) {
    this.oauthClients = oauthClients;
    this.auditEvents = auditEvents;
    this.allowOptionalPkce = allowOptionalPkce;
  }

  @Override
  @Transactional
  public void handle(final UpdateOAuthClientPkcePolicyCommand command) {
    final OAuthClient existing =
        oauthClients
            .findByClientId(command.clientId())
            .filter(found -> found.organizationId().equals(command.organizationId()))
            .orElseThrow(() -> new OAuthClientNotFoundException(command.clientId()));

    if (!existing.active()) {
      throw new OAuthClientInactiveException(command.clientId());
    }
    if (existing.pkcePolicy() == command.pkcePolicy()) {
      return;
    }
    // ADR-0043: clavaris.oauth.pkce.allow-optional=false closes the relaxed policy. Tightening a
    // client is still allowed: the switch only ever removes the way to remove a requirement.
    if (command.pkcePolicy() == PkcePolicy.OPTIONAL && !allowOptionalPkce) {
      throw new OptionalPkceNotAllowedException();
    }

    oauthClients.save(existing.updatePkcePolicy(command.pkcePolicy()));

    auditEvents.write(
        command.actor(),
        "oauth_client.pkce_policy_updated",
        "Organization",
        existing.organizationId().toString(),
        "clientId="
            + command.clientId()
            + " from="
            + existing.pkcePolicy()
            + " to="
            + command.pkcePolicy());
  }
}
