package com.clavaris.organization.application.usecase.createworkspace;

import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.organization.domain.model.Workspace;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaWorkspaceRepository}. Parked under {@code
 * createworkspace} because that's this module's first Workspace use case, not because {@code
 * findById}/{@code findKeysetPageByOrganizationId} are scoped to it — {@code addworkspacemember}
 * and {@code listworkspacesfororganization} are the other consumers, same precedent this module's
 * own {@code OrganizationRepository} already established.
 */
public interface WorkspaceRepository {

  void save(Workspace workspace);

  Optional<Workspace> findById(UUID workspaceId);

  /**
   * TD-PERF-020 (keyset revision, 2026-09-14): a page of an Organization's workspaces, oldest
   * first. TD-PERF-032: the only way to list them — there is no unbounded variant any more; the
   * REST admin API pages for its caller and the dashboard listing reads bounded pages.
   */
  KeysetPage<Workspace> findKeysetPageByOrganizationId(
      UUID organizationId, KeysetPageRequest pageRequest);

  // webhook-module's EventOutboxWriter needs organizationId, not the full Workspace — same
  // "scalar projection over full-entity lookup" precedent as AccountRepository's own identical
  // method (code review, 2026-09-01). The only two callers (RemoveWorkspaceMemberService,
  // ChangeWorkspaceMemberRoleService) start from a WorkspaceMembership, which only carries
  // workspaceId, not organizationId.
  Optional<UUID> findOrganizationIdById(UUID workspaceId);

  // Delete Workspace feature, 2026-09-28: a real hard delete, backed by the DB-level cascade to
  // workspace_memberships/workspace_teams (see DeleteWorkspaceService's own Javadoc for exactly
  // what this does and does not erase) — no application-layer eraser loop needed.
  void deleteById(UUID workspaceId);
}
