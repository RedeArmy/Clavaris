package com.clavaris.organization.application.usecase.deleteorganization;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.event.OrganizationDeletedEvent;
import com.clavaris.organization.domain.model.Organization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * BR-DATA-02/03's own organization-level equivalent: reachable via {@code POST
 * /api/v1/admin/organizations/{organizationId}:delete} (operator, {@code
 * AuditActor#platformClient}) or the dashboard's own Danger Zone (self-service, {@code
 * AuditActor#platformAccount}, gated behind a real confirmation flow — see {@code
 * PlatformDeleteOrganizationController}'s own Javadoc) — a real, permanent hard delete of an entire
 * consuming system's own account pool, still the single most destructive operation this management
 * API exposes regardless of which caller triggers it.
 *
 * <p><b>Corrected, TD-FUT-032 (2026-09-13):</b> this Javadoc previously said "never self-service,
 * never triggered by the Organization's own owning {@code PlatformAccount}." See {@code
 * DeleteOrganizationCommand}'s own Javadoc for the same correction, in full.
 *
 * <p>SDE-III review, 2026-09-13 — two real bugs found and closed in the same pass as the dashboard
 * widening above, not by it: (1) an already-{@code PRODUCTION}-linked Organization's own {@code
 * linkedEnvironmentOrganizationId} self-referencing FK has no {@code ON DELETE} clause, so deleting
 * either side of a linked pair previously raised a raw, unhandled foreign-key-violation from
 * Postgres — this class now unlinks the surviving sibling first (see {@link
 * com.clavaris.organization.domain.model.Organization#withoutLinkedEnvironment}), in the same
 * transaction. (2) {@link OrganizationWebhookDataEraser} closes a real, previously-silent gap: this
 * class erased identity-module/client-registry-module data from day one, but never webhook-module's
 * (ADR-0007, shipped 2026-09-02, after this service was first written) — every {@code
 * WebhookEndpoint}/{@code WebhookDelivery} row survived a delete as an orphan until this fix.
 *
 * <p><b>Erasure is application-layer here, not database-cascade</b> — a deliberate departure from
 * individual account deletion's own approach (which does cascade at the DB level, migration {@code
 * V20260826100000}). A cross-module FK from identity-module's {@code accounts}/{@code signing_keys}
 * or client-registry-module's {@code oauth_clients} to this module's own {@code organizations}
 * table was tried first and reverted: each module's own Testcontainers-backed test suite only scans
 * its own {@code db/migration} folder (no cross-module Maven dependency exists between the business
 * modules), so a migration in one module referencing another module's table fails that module's own
 * isolated tests with "relation does not exist", even though it passes a combined, full-{@code
 * app}-context verification. Real, load-bearing lesson: cross-module referential integrity in this
 * codebase must be enforced here, explicitly, not via a DB-level FK. {@link
 * OrganizationTokenRevoker}, {@link OrganizationIdentityDataEraser}, and {@link
 * OrganizationOAuthClientsEraser} are the three ports that do it — {@code organizations.deleteById}
 * itself now only cascades (this module's own migration, same-module, no isolation issue) to {@code
 * rate_limit_policies}.
 *
 * <p>Order matters for the first two calls only: {@link OrganizationTokenRevoker} must run before
 * {@link OrganizationIdentityDataEraser}/{@link OrganizationOAuthClientsEraser} because its own SQL
 * queries {@code accounts}/{@code oauth_clients} by organization to find the {@code
 * oauth2_authorization} rows to delete (same reasoning identity-module's own {@code
 * AccountTokenRevoker} already established) — those rows must still exist when it runs. The two
 * erasers have no ordering dependency on each other.
 *
 * <p><b>No explicit `Workspace`/`WorkspaceMembership` erasure step here</b> — deliberately, not a
 * gap: unlike the two erasers above (needed because identity-module/client-registry-module are
 * separate modules with no shared migration folder, see this Javadoc's own reasoning), {@code
 * workspaces}/{@code workspace_memberships} are this same module's own tables, both {@code ON
 * DELETE CASCADE} from {@code organizations}/{@code workspaces} respectively (migrations {@code
 * V20260827130000}/{@code V20260827130001}) — the plain {@code organizations.deleteById} call below
 * already erases every Workspace and WorkspaceMembership row for this Organization, no
 * application-layer code needed.
 *
 * <p>TD-ARCH-007 (SDE-III review, 2026-08-26): {@link EventOutboxWriter}/{@link
 * OrganizationDeletedEvent} added — this class's identity-module sibling, {@code
 * DeleteAccountService}, already wrote an outbox event on delete; this one never did, a real
 * inconsistency between two structurally parallel services now closed. {@code webhook-module}
 * (ADR-0007) shipped 2026-09-02 and now drains this write for real. The read this needs ({@link
 * OrganizationRepository#findById}) replaces the plain {@code existsById} check this class used
 * before — the full {@code Organization} (specifically its {@code name}) is needed to build the
 * event payload.
 */
// Literals: the repeated string is "PMD.LongVariable" itself, used on the constructor's four
// port parameters — same rationale as identity-module's own IdentityUseCaseConfig class-level
// suppression for this exact PMD-annotation-string-as-literal false positive.
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public class DeleteOrganizationService implements DeleteOrganizationUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(DeleteOrganizationService.class);

  private final OrganizationRepository organizations;

  @SuppressWarnings("PMD.LongVariable") // matches the port's own name, same precedent as
  // identity-module's own DeleteAccountService field for AccountTokenRevoker.
  private final OrganizationTokenRevoker organizationTokenRevoker;

  @SuppressWarnings("PMD.LongVariable")
  private final OrganizationIdentityDataEraser identityDataEraser;

  @SuppressWarnings("PMD.LongVariable")
  private final OrganizationOAuthClientsEraser oauthClientsEraser;

  private final OrganizationWebhookDataEraser webhookDataEraser;

  private final AuditEventRecorder auditEvents;
  private final EventOutboxWriter outbox;

  @SuppressWarnings("java:S107") // one parameter per collaborating port — same rationale as
  // identity-module's DeleteAccountService/ConfirmPasswordResetService's own identical
  // suppression: this cascade genuinely needs every one of these, not excess complexity to hide.
  public DeleteOrganizationService(
      final OrganizationRepository organizations,
      @SuppressWarnings("PMD.LongVariable") final OrganizationTokenRevoker organizationTokenRevoker,
      @SuppressWarnings("PMD.LongVariable") final OrganizationIdentityDataEraser identityDataEraser,
      @SuppressWarnings("PMD.LongVariable") final OrganizationOAuthClientsEraser oauthClientsEraser,
      final OrganizationWebhookDataEraser webhookDataEraser,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox) {
    this.organizations = organizations;
    this.organizationTokenRevoker = organizationTokenRevoker;
    this.identityDataEraser = identityDataEraser;
    this.oauthClientsEraser = oauthClientsEraser;
    this.webhookDataEraser = webhookDataEraser;
    this.auditEvents = auditEvents;
    this.outbox = outbox;
  }

  // PMD.GuardLogStatement false positive — same rationale as every other logging call site in
  // this codebase (e.g. DeleteAccountService's own identical suppression).
  @SuppressWarnings("PMD.GuardLogStatement")
  @Override
  @Transactional
  public void handle(final DeleteOrganizationCommand command) {
    // findById, not the plain existsById this class used before TD-ARCH-007: the full
    // Organization (specifically its name) is needed below to build OrganizationDeletedEvent —
    // same reasoning identity-module's own DeleteAccountService already established for Account.
    final Organization organization =
        organizations
            .findById(command.organizationId())
            .orElseThrow(() -> new OrganizationNotFoundException(command.organizationId()));

    // Real bug, SDE-III review 2026-09-13: the surviving sibling of an already-promoted
    // DEVELOPMENT/PRODUCTION pair must be unlinked before this row's own DELETE runs below, or
    // Postgres raises a raw foreign-key-violation on the self-referencing
    // linked_environment_organization_id column — see Organization#withoutLinkedEnvironment's own
    // Javadoc and this class's own class-level Javadoc.
    organization
        .linkedEnvironmentOrganizationId()
        .flatMap(organizations::findById)
        .ifPresent(sibling -> organizations.save(sibling.withoutLinkedEnvironment()));

    // Must run before the erasure calls below remove the accounts/oauth_clients rows this port's
    // own implementation queries by organizationId — see this class's own Javadoc.
    organizationTokenRevoker.revokeAllTokensFor(command.organizationId());

    // No ordering dependency between these three — see this class's own Javadoc.
    identityDataEraser.eraseAllFor(command.organizationId());
    oauthClientsEraser.eraseAllFor(command.organizationId());
    webhookDataEraser.eraseAllFor(command.organizationId());

    auditEvents.write(
        command.actor(),
        "organization.deleted",
        "Organization",
        command.organizationId().toString(),
        null);

    outbox.write(
        "Organization",
        "organization.deleted",
        organization.id(),
        organization.id(),
        OrganizationDeletedEvent.from(organization));

    LOG.info("event=organization_deleted organizationId={}", command.organizationId());

    organizations.deleteById(command.organizationId());
  }
}
