package com.clavaris.organization.application.usecase.deleteorganization;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * BR-DATA-02/03's own organization-level equivalent: {@code POST
 * /api/v1/admin/organizations/{organizationId}:delete} — a real, permanent hard delete of an entire
 * consuming system's own account pool. Never self-service, never triggered by the Organization's
 * own owning {@code PlatformAccount} — the single most destructive operation this management API
 * exposes, gated by its own dedicated scope for that reason.
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
 * <p><b>No `Workspace`/`WorkspaceMembership` step exists here either</b>, same reason {@code
 * DeleteAccountService} doesn't have one — zero code exists for that feature yet
 * (`roadmap-and-release-plan.md` §2). Revisit the day it ships.
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

  private final AuditEventRecorder auditEvents;

  public DeleteOrganizationService(
      final OrganizationRepository organizations,
      @SuppressWarnings("PMD.LongVariable") final OrganizationTokenRevoker organizationTokenRevoker,
      @SuppressWarnings("PMD.LongVariable") final OrganizationIdentityDataEraser identityDataEraser,
      @SuppressWarnings("PMD.LongVariable") final OrganizationOAuthClientsEraser oauthClientsEraser,
      final AuditEventRecorder auditEvents) {
    this.organizations = organizations;
    this.organizationTokenRevoker = organizationTokenRevoker;
    this.identityDataEraser = identityDataEraser;
    this.oauthClientsEraser = oauthClientsEraser;
    this.auditEvents = auditEvents;
  }

  // PMD.GuardLogStatement false positive — same rationale as every other logging call site in
  // this codebase (e.g. DeleteAccountService's own identical suppression).
  @SuppressWarnings("PMD.GuardLogStatement")
  @Override
  @Transactional
  public void handle(final DeleteOrganizationCommand command) {
    if (!organizations.existsById(command.organizationId())) {
      throw new OrganizationNotFoundException(command.organizationId());
    }

    // Must run before the erasure calls below remove the accounts/oauth_clients rows this port's
    // own implementation queries by organizationId — see this class's own Javadoc.
    organizationTokenRevoker.revokeAllTokensFor(command.organizationId());

    // No ordering dependency between these two — see this class's own Javadoc.
    identityDataEraser.eraseAllFor(command.organizationId());
    oauthClientsEraser.eraseAllFor(command.organizationId());

    auditEvents.write(
        command.actor(),
        "organization.deleted",
        "Organization",
        command.organizationId().toString(),
        null);

    LOG.info("event=organization_deleted organizationId={}", command.organizationId());

    organizations.deleteById(command.organizationId());
  }
}
