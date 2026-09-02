package com.clavaris.organization.application.usecase.addworkspacemember;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.domain.event.WorkspaceMemberAddedEvent;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orchestration for {@link AddWorkspaceMemberUseCase} — BR-WS-04: provisions a brand-new {@code
 * Account} for every call (v1 has no "attach an existing Account to a second Workspace" flow, only
 * "create a new member"), never annotated {@code @Transactional} on {@link #handle} itself.
 *
 * <p>Deliberately structured as two separate units of work, not one flat transaction:
 *
 * <ol>
 *   <li>{@link #accountProvisioner}'s own call — commits a new {@code Account} in identity-module
 *       and sends a real "set your password" email, entirely outside any transaction this class
 *       opens.
 *   <li>{@link #transactionTemplate}, used explicitly (not a self-invoked {@code @Transactional}
 *       private method, which Spring's own proxy-based AOP would silently not intercept) to save
 *       the {@code WorkspaceMembership} row and write the audit/outbox events for it atomically.
 * </ol>
 *
 * <p><b>TD-WS-001, closed:</b> if step 2 fails immediately after step 1 succeeds, the new {@code
 * Account} would exist with no {@code WorkspaceMembership} yet — an orphan. Rare (a DB failure in
 * the instant after a prior, unrelated write already committed), still not one of the security
 * invariants ADR-0007 treats as unacceptable eventual-consistency (that bar is reserved for
 * session/token revocation) — but real enough, and cheap enough to close, that it no longer needs
 * an operator to notice and hand-delete the orphan via the admin API. {@link
 * AccountProvisioner#deprovision} is the compensating action (a saga, not a distributed
 * transaction): on any failure in step 2, this class calls it to reverse step 1, then rethrows the
 * original failure unchanged — the caller still sees the real error, the orphan just no longer
 * outlives it.
 */
// PMD.LongVariable: accountProvisioner/transactionTemplate match their own collaborator type
// names exactly — same convention every other port field in this codebase follows (e.g.
// accountTokenRevoker, workspaceMembershipEraser), not an organically long name that should
// shrink. PMD.AvoidCatchingGenericException: TD-WS-001's own compensating-action saga needs both
// catches to be this broad — the membership-write step can fail for any reason a real transaction
// can (constraint violation, connection loss, ...), and the compensating deprovision() call can
// likewise fail for any reason a real cross-module use-case call can; narrowing either to a
// specific exception type would leave some real failure mode uncompensated/unlogged, defeating
// the whole point of this catch. PMD.GuardLogStatement: same false positive every other logging
// call site in this module already documents — accountId is a cheap in-memory accessor, not an
// expensive computation the WARN level should be checked before evaluating.
@SuppressWarnings({
  "PMD.LongVariable",
  "PMD.AvoidCatchingGenericException",
  "PMD.GuardLogStatement"
})
public class AddWorkspaceMemberService implements AddWorkspaceMemberUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(AddWorkspaceMemberService.class);

  private final WorkspaceRepository workspaces;
  private final WorkspaceMembershipRepository memberships;
  private final AccountProvisioner accountProvisioner;
  private final AuditEventRecorder auditEvents;
  private final EventOutboxWriter outbox;
  private final TransactionTemplate transactionTemplate;

  @SuppressWarnings("java:S107") // one parameter per collaborating port — same rationale as
  // DeleteOrganizationService's own identical suppression: this flow genuinely needs every one.
  public AddWorkspaceMemberService(
      final WorkspaceRepository workspaces,
      final WorkspaceMembershipRepository memberships,
      final AccountProvisioner accountProvisioner,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox,
      final TransactionTemplate transactionTemplate) {
    this.workspaces = workspaces;
    this.memberships = memberships;
    this.accountProvisioner = accountProvisioner;
    this.auditEvents = auditEvents;
    this.outbox = outbox;
    this.transactionTemplate = transactionTemplate;
  }

  @Override
  public WorkspaceMembership handle(final AddWorkspaceMemberCommand command) {
    final Workspace workspace =
        workspaces
            .findById(command.workspaceId())
            .orElseThrow(() -> new WorkspaceNotFoundException(command.workspaceId()));

    // Outside any transaction this class opens — see this class's own Javadoc.
    final AccountProvisioner.ProvisionedAccount account =
        accountProvisioner.provisionAndSendWelcome(workspace.organizationId(), command.email());

    try {
      return transactionTemplate.execute(
          status -> {
            final WorkspaceMembership membership =
                WorkspaceMembership.join(workspace.id(), account.accountId(), command.role());
            memberships.save(membership);

            auditEvents.write(
                command.actor(),
                "workspace_membership.added",
                "WorkspaceMembership",
                membership.id().toString(),
                "workspaceId=" + workspace.id() + " role=" + command.role());

            outbox.write(
                "WorkspaceMembership",
                "workspace_membership.added",
                membership.id(),
                WorkspaceMemberAddedEvent.from(membership));

            return membership;
          });
    } catch (final RuntimeException membershipWriteFailed) {
      // TD-WS-001: step 1 (the Account) already committed — compensate rather than leave an
      // orphan. deprovision() may itself fail (see its own Javadoc) — contained here, never
      // allowed to mask or replace the original failure below.
      try {
        accountProvisioner.deprovision(account.accountId(), command.actor());
      } catch (final RuntimeException deprovisionFailed) {
        LOG.warn(
            "event=workspace_member_account_deprovision_failed accountId={}",
            account.accountId(),
            deprovisionFailed);
      }
      throw membershipWriteFailed;
    }
  }
}
