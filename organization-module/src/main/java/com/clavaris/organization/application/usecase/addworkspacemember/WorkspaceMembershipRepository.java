package com.clavaris.organization.application.usecase.addworkspacemember;

import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaWorkspaceMembershipRepository}. Parked under {@code
 * addworkspacemember} because that's this module's first membership-mutating use case, not because
 * every method is scoped to it — {@code changeworkspacememberrole}/{@code removeworkspacemember}/
 * {@code listworkspacemembers} are the other consumers, same "parked under the first use case"
 * precedent this module's own {@code OrganizationRepository}/{@code WorkspaceRepository} already
 * established.
 */
public interface WorkspaceMembershipRepository {

  void save(WorkspaceMembership membership);

  Optional<WorkspaceMembership> findByWorkspaceIdAndAccountId(UUID workspaceId, UUID accountId);

  List<WorkspaceMembership> findAllByWorkspaceId(UUID workspaceId);

  /**
   * {@code WorkspaceRoleClaimsCustomizer}'s own lookup (app module) — resolves whether an
   * authenticated Account should carry a {@code workspace_role} claim on login. Returns every
   * membership, not just the first, even though v1's own provisioning flow ({@code
   * AddWorkspaceMemberService} always creates a brand-new Account, never attaches an existing one
   * to a second Workspace) means a single-element result is the only case that can occur today —
   * correctness here shouldn't depend on that flow never changing.
   */
  List<WorkspaceMembership> findAllByAccountId(UUID accountId);

  /**
   * BR-WS-01's replacement invariant ("a workspace must always retain at least one ADMIN") — {@code
   * changeworkspacememberrole}/{@code removeworkspacemember} both need this count *before*
   * demoting/removing an ADMIN, without paying for a full row fetch just to count them.
   */
  long countByWorkspaceIdAndRole(UUID workspaceId, WorkspaceRole role);

  void deleteById(UUID membershipId);

  /**
   * TD-ARCH (ADR-0007, Workspace feature 2026-08-27): {@code WorkspaceMembershipEraserBridge}'s own
   * cross-module cascade — called from identity-module's {@code DeleteAccountService} before an
   * {@code Account} row disappears, so no membership row is ever left pointing at a deleted
   * Account.
   */
  void deleteAllByAccountId(UUID accountId);
}
