package com.clavaris.organization.application.usecase.createworkspaceteam;

import com.clavaris.organization.domain.model.WorkspaceTeam;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
 *
 * <p>PMD.TooManyMethods (TD-SEC-060's own {@link #saveAndFlush} pushed this past the default
 * threshold): every method here backs a real, distinct use case this port's several consumers
 * actually need — same "one port, several use cases" shape {@code AccountRepository}'s own
 * identical suppression documents, not a design smell to split up.
 */
@SuppressWarnings("PMD.TooManyMethods")
public interface WorkspaceTeamRepository {

  void save(WorkspaceTeam team);

  /**
   * TD-SEC-060: same write as {@link #save}, but flushed immediately rather than deferred to the
   * enclosing transaction's own commit — {@code CreateWorkspaceTeamService}/{@code
   * RenameWorkspaceTeamService} need the {@code ux_workspace_teams_workspace_id_name} constraint
   * violation to surface synchronously, catchable right where it's thrown, not at a commit boundary
   * the calling method has already returned past — same "a dedicated flushed write for the
   * race-sensitive caller, plain {@link #save} everywhere else" precedent {@code
   * WorkspaceRoleRepository#saveAndFlush} already establishes for an identical shape of race.
   */
  void saveAndFlush(WorkspaceTeam team);

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
   * TD-PERF-030: the batched sibling of {@link #findRoleIdsByTeamId} — one query for every team in
   * {@code teamIds}, instead of one {@link #findRoleIdsByTeamId} call per team in a caller's own
   * loop ({@code PlatformWorkspaceController#loadTeamsAndRoles}, {@code
   * PlatformAccountWorkspaceRoleController#populateRoleOptions} both used to do exactly that). Same
   * "never a missing map key" contract {@code PlatformWorkspaceController
   * #populateTeamsHierarchyModel}'s own {@code accountIdsByRoleId} already establishes — every id
   * in {@code teamIds} gets a (possibly empty) entry, so a caller never has to null-check {@code
   * .get(teamId)}.
   */
  Map<UUID, List<UUID>> findRoleIdsByTeamIds(Collection<UUID> teamIds);

  /**
   * The team {@code roleId} is currently grouped under, among this Workspace's own teams — empty if
   * the role isn't grouped into any of them (ADR-0028's own "at most one team per Workspace"
   * invariant, the check this method backs).
   */
  Optional<UUID> findTeamIdForRoleInWorkspace(UUID workspaceId, UUID roleId);

  /**
   * TD-ARCH-026: ADR-0028 §2's "at most one team per role per Workspace" invariant is enforced only
   * at the application layer ({@link
   * com.clavaris.organization.application.usecase.addroletoworkspaceteam.AddRoleToWorkspaceTeamService}'s
   * own check-then-insert against {@link #findTeamIdForRoleInWorkspace}, then {@link
   * #addRoleToTeam}) — {@code workspace_team_roles}'s own migration explicitly documents this as
   * app-layer-only, not DB-enforced. Two concurrent {@code addRoleToTeam} calls for the same role
   * but different teams could both pass the "not already grouped" check and both insert. A
   * transaction-scoped Postgres advisory lock ({@code pg_advisory_xact_lock}, keyed on {@code
   * workspaceId} and {@code roleId} together, auto-released at commit or rollback) serializes every
   * caller attempting to group the same role within the same Workspace — same mechanism, same
   * reasoning, as {@code WorkspaceMembershipRepository#lockForRoleChange}'s own identical fix for
   * an analogous race. Must be invoked before {@link #findTeamIdForRoleInWorkspace}, not after.
   */
  void lockForTeamRoleChange(UUID workspaceId, UUID roleId);

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
