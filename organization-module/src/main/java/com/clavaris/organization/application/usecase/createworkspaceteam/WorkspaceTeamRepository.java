package com.clavaris.organization.application.usecase.createworkspaceteam;

import com.clavaris.organization.domain.model.WorkspaceTeam;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaWorkspaceTeamRepository}. ADR-0028: {@link
 * WorkspaceTeam} is Workspace-scoped; its role associations are a separate join
 * (`workspace_team_roles`), never a field on {@code WorkspaceRole} (see that ADR's own §2). Parked
 * under {@code createworkspaceteam}, same "first consumer owns the port" precedent {@code
 * WorkspaceRoleRepository} (parked under {@code createworkspace}) already establishes.
 */
public interface WorkspaceTeamRepository {

  void save(WorkspaceTeam team);

  Optional<WorkspaceTeam> findById(UUID teamId);

  /**
   * Every team defined for one Workspace — deliberately not paginated, same "the whole set is small
   * and every consumer needs it as a whole" rationale {@code WorkspaceRoleRepository
   * #findAllByOrganizationId}'s own Javadoc documents.
   */
  List<WorkspaceTeam> findAllByWorkspaceId(UUID workspaceId);

  void deleteById(UUID teamId);

  /**
   * Adds {@code roleId} to {@code teamId}'s own role set — the caller ( {@code
   * AddRoleToWorkspaceTeamService}) has already confirmed the role isn't grouped elsewhere in this
   * same Workspace (ADR-0028: at most one team per role per Workspace).
   */
  void addRoleToTeam(UUID teamId, UUID roleId);

  void removeRoleFromTeam(UUID teamId, UUID roleId);

  /** Every roleId currently grouped under this team. */
  List<UUID> findRoleIdsByTeamId(UUID teamId);

  /**
   * The team {@code roleId} is currently grouped under, among this Workspace's own teams — empty if
   * the role isn't grouped into any of them (ADR-0028's own "at most one team per Workspace"
   * invariant, the check this method backs).
   */
  Optional<UUID> findTeamIdForRoleInWorkspace(UUID workspaceId, UUID roleId);

  /**
   * Every roleId grouped into any team belonging to this Workspace — the complement of "ungrouped
   * roles" the Workspace-detail page's own Teams section needs to render, without an N+1 query per
   * team.
   */
  Set<UUID> findAllGroupedRoleIdsForWorkspace(UUID workspaceId);

  /**
   * True if {@code roleId} is grouped into any team OTHER than {@code excludedTeamId} — org-wide,
   * deliberately NOT scoped to one Workspace: a role may be grouped differently (or not at all) in
   * a different Workspace of the same Organization (ADR-0028 §2). Backs {@code
   * DeleteWorkspaceTeamService}'s own "only fully delete a role this team's own deletion orphans,
   * never one still grouped somewhere else" guard, live UX request 2026-09-28.
   */
  boolean isRoleGroupedInAnyOtherTeam(UUID roleId, UUID excludedTeamId);
}
