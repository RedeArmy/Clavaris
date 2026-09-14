package com.clavaris.organization.application.usecase.getauditlogfororganization;

import com.clavaris.common.application.port.AuditEventReader;
import com.clavaris.common.domain.model.AuditEvent;
import com.clavaris.common.domain.model.AuditEventTargetRef;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.application.usecase.listworkspacesfororganization.ListWorkspacesForOrganizationQuery;
import com.clavaris.organization.application.usecase.listworkspacesfororganization.ListWorkspacesForOrganizationUseCase;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-0025: the dashboard's own audit-log query — the one item {@code technical-debt-register.md}'s
 * own TD-FUT-032 row named as a genuine capability gap, not just "not wired yet" (audit events are
 * written, {@link com.clavaris.common.application.port.AuditEventRecorder}, but were never read
 * back anywhere before this). {@code audit_events} carries no {@code organization_id} column by
 * design (see that table's own migration comment) — this service builds an Organization-scoped view
 * by resolving every {@link AuditEventTargetRef} this Organization's own resources are actually
 * recorded under, then delegating to {@link AuditEventReader}, the one primitive that port exposes.
 *
 * <p><b>In scope</b> (every {@code target_type}/{@code action} actually written against these
 * resources, confirmed by reading every {@code auditEvents.write(...)} call site in the codebase):
 * the Organization's own configuration changes ({@code organization.*}, {@code
 * rate_limit_policy.set}, {@code social_login_policy.set}, {@code
 * account_authentication_policy.set}, {@code organization_social_credential.*}, Secret Key/{@code
 * organization_client.*}, {@code oauth_client.registered}/{@code .deactivated}/{@code
 * .secret_rotated}, {@code signing_key.*} — all of these already target {@code "Organization"}
 * itself, so a single {@code ("Organization", organizationId)} ref covers every one of them, no
 * fan-out needed); {@code Workspace}/{@code WorkspaceMembership} events (resolved via {@link
 * ListWorkspacesForOrganizationUseCase}, then a single batched {@link
 * WorkspaceMembershipRepository#findAllByWorkspaceIds} call across every Workspace found —
 * TD-PERF-021, not one {@code ListWorkspaceMembersUseCase} call per Workspace the way this method
 * originally worked); {@code OAuthClient}-scoped config ({@code client_branding.set}, {@code
 * client_domain_config.*}, {@code redirect_policy.set} — these target the client's own id, not
 * {@code "Organization"}, so they need the {@link OAuthClientIdsForAuditLogProvider} fan-out);
 * {@code WebhookEndpoint} lifecycle events (via {@link WebhookEndpointIdsForAuditLogProvider}).
 *
 * <p><b>Deliberately out of scope for this pass</b> (a real, documented limitation, not an
 * oversight): {@code Account}-level events ({@code account.suspended}/{@code
 * .impersonation_started}/session/password-reset events, etc.) would require enumerating every
 * {@code Account} id in this Organization's own pool — a much larger, higher-cardinality fan-out
 * than any resource type above (an Organization can have many end-user accounts), a genuinely
 * different, higher-volume audit stream than "administrative changes to my Organization's own
 * configuration," and not "cheap to hold in memory" the way every other list here is. {@code
 * webhook_delivery.replayed} events are also excluded — enumerating every delivery id for every
 * endpoint is a deeper fan-out than this page's own scope justifies; that history is already
 * visible on the endpoint's own Deliveries page. Both are real gaps for a future, dedicated
 * increment, not silently dropped — see this class's own technical-debt-register.md entry.
 */
@SuppressWarnings("PMD.LongVariable")
public class GetAuditLogForOrganizationService implements GetAuditLogForOrganizationUseCase {

  // Same "cheap to hold in memory, no real pagination yet" posture
  // ListWebhookDeliveriesForEndpointService's own MAX_RESULTS already establishes.
  private static final int MAX_RESULTS = 100;

  private final ListWorkspacesForOrganizationUseCase listWorkspaces;
  private final WorkspaceMembershipRepository memberships;
  private final OAuthClientIdsForAuditLogProvider oauthClientIds;
  private final WebhookEndpointIdsForAuditLogProvider webhookEndpointIds;
  private final AuditEventReader auditEvents;

  @SuppressWarnings("java:S107") // one parameter per collaborating port — same rationale as every
  // other multi-collaborator constructor in this codebase.
  public GetAuditLogForOrganizationService(
      final ListWorkspacesForOrganizationUseCase listWorkspaces,
      final WorkspaceMembershipRepository memberships,
      final OAuthClientIdsForAuditLogProvider oauthClientIds,
      final WebhookEndpointIdsForAuditLogProvider webhookEndpointIds,
      final AuditEventReader auditEvents) {
    this.listWorkspaces = listWorkspaces;
    this.memberships = memberships;
    this.oauthClientIds = oauthClientIds;
    this.webhookEndpointIds = webhookEndpointIds;
    this.auditEvents = auditEvents;
  }

  // SDE-III performance pass, 2026-09-13 (TD-PERF-021): this method used to call
  // ListWorkspaceMembersUseCase once per Workspace inside the loop below — a real, confirmed N+1
  // (an Organization with W Workspaces made W separate round trips just for membership ids), on top
  // of 2 more independent round trips (OAuth Client ids, Webhook Endpoint ids) and the final audit
  // query itself, none of it sharing a transaction. Two fixes, both now applied: (1)
  // @Transactional(readOnly = true) — one connection now covers the whole read instead of W+4
  // separate HikariCP (sized to 10, TD-PERF-007) checkouts, and the annotation documents genuine
  // read-only intent; (2) the loop itself no longer queries per Workspace — every Workspace id is
  // collected first, then a single WorkspaceMembershipRepository#findAllByWorkspaceIds(...) call
  // (one IN (...) query) returns every membership across every Workspace at once. Memberships are
  // grouped back into target refs directly (workspace id isn't needed for that, only the
  // membership's own id), so no second pass keyed by workspace is required.
  @Override
  @Transactional(readOnly = true)
  public List<AuditEvent> handle(final UUID organizationId) {
    final List<AuditEventTargetRef> targets = new ArrayList<>();
    targets.add(new AuditEventTargetRef("Organization", organizationId.toString()));

    final List<Workspace> workspaces =
        listWorkspaces.handle(new ListWorkspacesForOrganizationQuery(organizationId));
    final List<UUID> workspaceIds = workspaces.stream().map(Workspace::id).toList();
    for (final Workspace workspace : workspaces) {
      targets.add(new AuditEventTargetRef("Workspace", workspace.id().toString()));
    }
    for (final WorkspaceMembership membership : memberships.findAllByWorkspaceIds(workspaceIds)) {
      targets.add(new AuditEventTargetRef("WorkspaceMembership", membership.id().toString()));
    }

    for (final String oauthClientId : oauthClientIds.oauthClientIds(organizationId)) {
      targets.add(new AuditEventTargetRef("OAuthClient", oauthClientId));
    }
    for (final String webhookEndpointId : webhookEndpointIds.webhookEndpointIds(organizationId)) {
      targets.add(new AuditEventTargetRef("WebhookEndpoint", webhookEndpointId));
    }

    return auditEvents.findRecentForTargets(targets, MAX_RESULTS);
  }
}
