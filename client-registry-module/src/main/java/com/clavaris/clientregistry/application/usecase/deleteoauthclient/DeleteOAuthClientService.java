package com.clavaris.clientregistry.application.usecase.deleteoauthclient;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.common.application.port.AuditEventRecorder;

/**
 * Live UX request, 2026-09-24: the first single-entity (not cascade-from-Organization-delete) hard
 * delete for an {@code OAuthClient} in this codebase. Same ownership-verification/audit shape as
 * {@code deactivateoauthclient.DeactivateOAuthClientService}, plus two extra guards a deactivation
 * never needed: the client must already be inactive ({@link OAuthClientActiveException}), and any
 * lingering {@code oauth2_authorization} rows are revoked before the row itself is deleted ({@link
 * OAuthClientTokenRevoker} — see that port's own Javadoc for why this table has no FK to enforce it
 * structurally).
 */
public class DeleteOAuthClientService implements DeleteOAuthClientUseCase {

  private final OAuthClientRepository oauthClients;
  private final OAuthClientTokenRevoker tokenRevoker;
  private final AuditEventRecorder auditEvents;

  public DeleteOAuthClientService(
      final OAuthClientRepository oauthClients,
      final OAuthClientTokenRevoker tokenRevoker,
      final AuditEventRecorder auditEvents) {
    this.oauthClients = oauthClients;
    this.tokenRevoker = tokenRevoker;
    this.auditEvents = auditEvents;
  }

  @Override
  public void handle(final DeleteOAuthClientCommand command) {
    final OAuthClient existing =
        oauthClients
            .findByClientId(command.clientId())
            // Same cross-tenant-mismatch-collapses-to-404 reasoning as
            // DeactivateOAuthClientService's own identical check.
            .filter(found -> found.organizationId().equals(command.organizationId()))
            .orElseThrow(() -> new OAuthClientNotFoundException(command.clientId()));

    if (existing.active()) {
      throw new OAuthClientActiveException(command.clientId());
    }

    // Must run before the delete below — see OAuthClientTokenRevoker's own Javadoc for why this
    // table has no FK to enforce that ordering structurally.
    tokenRevoker.revokeAllTokensFor(existing.id());
    oauthClients.delete(existing);

    // Never the raw secret hash — same BR-DATA-01 discipline as every other audited action in
    // this module. No "clientId=" value here, deliberately unlike every sibling event above: the
    // client itself no longer exists to look up by that id after this line, so the audit row
    // stands on its own target reference instead.
    auditEvents.write(
        command.actor(),
        "oauth_client.deleted",
        "Organization",
        existing.organizationId().toString(),
        "deletedClientId=" + command.clientId());
  }
}
