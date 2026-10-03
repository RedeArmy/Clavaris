package com.clavaris.clientregistry.application.usecase.deleteorganizationclient;

import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientRepository;
import com.clavaris.clientregistry.application.usecase.deleteoauthclient.OAuthClientTokenRevoker;
import com.clavaris.clientregistry.domain.model.OrganizationClient;
import com.clavaris.common.application.port.AuditEventRecorder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hard delete of a single Secret Key. Same shape as {@code DeleteOAuthClientService}: ownership is
 * verified (a cross-tenant {@code clientId} is a 404), the key must already be inactive, and any
 * lingering {@code oauth2_authorization} rows are revoked first - {@code
 * oauth2_authorization.registered_client_id} has no FK, and the platform issuer mints every {@code
 * RegisteredClient} for an OrganizationClient with its internal {@code id}, which is exactly what
 * {@link OAuthClientTokenRevoker} (reused as-is, it only needs that id) deletes by.
 */
@SuppressWarnings("PMD.LongVariable")
public class DeleteOrganizationClientService implements DeleteOrganizationClientUseCase {

  private final OrganizationClientRepository organizationClients;
  private final OAuthClientTokenRevoker tokenRevoker;
  private final AuditEventRecorder auditEvents;

  public DeleteOrganizationClientService(
      final OrganizationClientRepository organizationClients,
      final OAuthClientTokenRevoker tokenRevoker,
      final AuditEventRecorder auditEvents) {
    this.organizationClients = organizationClients;
    this.tokenRevoker = tokenRevoker;
    this.auditEvents = auditEvents;
  }

  @Override
  @Transactional
  public void handle(final DeleteOrganizationClientCommand command) {
    final OrganizationClient existing =
        organizationClients
            .findByClientId(command.clientId())
            .filter(found -> found.organizationId().equals(command.organizationId()))
            .orElseThrow(() -> new OrganizationClientNotFoundException(command.clientId()));

    if (existing.active()) {
      throw new OrganizationClientActiveException(command.clientId());
    }

    // Must run before the delete: there is no FK to enforce that ordering structurally.
    tokenRevoker.revokeAllTokensFor(existing.id());
    organizationClients.delete(existing);

    // Never the secret hash (BR-DATA-01); the deleted id stands on the audit row itself since the
    // client can no longer be looked up afterwards.
    auditEvents.write(
        command.actor(),
        "organization_client.deleted",
        "Organization",
        existing.organizationId().toString(),
        "deletedClientId=" + command.clientId());
  }
}
