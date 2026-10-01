package com.clavaris.organization.infrastructure.adapter.out.persistence;

import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamRepository;
import com.clavaris.organization.domain.model.WorkspaceTeam;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Implements the outbound port; maps between {@code domain.model.WorkspaceTeam} and {@link
 * WorkspaceTeamEntity}. {@code workspace_team_roles} carries no relationship mapping (see {@link
 * WorkspaceTeamRoleEntity}'s own Javadoc) — every Workspace-to-team-roles query here resolves this
 * Workspace's own team ids first, then filters the join table by them, in two plain queries rather
 * than a JPA join.
 *
 * <p>PMD.TooManyMethods: every method here backs a real, distinct {@code WorkspaceTeamRepository}
 * port method this module's use cases actually need — same "one port, several use cases" shape
 * {@code JpaWorkspaceMembershipRepository}'s own identical suppression documents, not a design
 * smell to split up.
 */
@SuppressWarnings("PMD.TooManyMethods")
@Repository
class JpaWorkspaceTeamRepository implements WorkspaceTeamRepository {

  private final SpringDataWorkspaceTeamJpaRepository teams;
  private final SpringDataWorkspaceTeamRoleJpaRepository teamRoles;
  private final JdbcTemplate jdbcTemplate;

  /* package */ JpaWorkspaceTeamRepository(
      final SpringDataWorkspaceTeamJpaRepository teams,
      final SpringDataWorkspaceTeamRoleJpaRepository teamRoles,
      final JdbcTemplate jdbcTemplate) {
    this.teams = teams;
    this.teamRoles = teamRoles;
    this.jdbcTemplate = jdbcTemplate;
  }

  // See WorkspaceTeamRepository#lockForTeamRoleChange's own Javadoc. Plain JdbcTemplate, not a
  // Spring Data native @Query method — same reasoning
  // JpaWorkspaceMembershipRepository#lockForRoleChange's own identical comment documents. The two
  // ids are combined into one string key (rather than two separate hashtext(?) calls combined
  // with a bitwise op) — the simplest way to get one lock keyed on the pair, no established
  // composite-key precedent existed yet to follow instead.
  @Override
  public void lockForTeamRoleChange(final UUID workspaceId, final UUID roleId) {
    jdbcTemplate.query(
        "SELECT pg_advisory_xact_lock(hashtext(?))",
        resultSet -> {
          /* side-effecting call — the lock itself is the point, not this row */
        },
        workspaceId + ":" + roleId);
  }

  @Override
  public void save(final WorkspaceTeam team) {
    teams.save(
        new WorkspaceTeamEntity(team.id(), team.workspaceId(), team.name(), team.createdAt()));
  }

  @Override
  public void saveAndFlush(final WorkspaceTeam team) {
    teams.saveAndFlush(
        new WorkspaceTeamEntity(team.id(), team.workspaceId(), team.name(), team.createdAt()));
  }

  @Override
  public Optional<WorkspaceTeam> findById(final UUID teamId) {
    return teams.findById(teamId).map(JpaWorkspaceTeamRepository::toDomain);
  }

  @Override
  public List<WorkspaceTeam> findAllByWorkspaceId(final UUID workspaceId) {
    return teams.findAllByWorkspaceId(workspaceId).stream()
        .map(JpaWorkspaceTeamRepository::toDomain)
        .toList();
  }

  @Override
  public void deleteById(final UUID teamId) {
    teams.deleteById(teamId);
  }

  @Override
  public void addRoleToTeam(final UUID teamId, final UUID roleId) {
    teamRoles.save(new WorkspaceTeamRoleEntity(teamId, roleId));
  }

  @Override
  public void removeRoleFromTeam(final UUID teamId, final UUID roleId) {
    teamRoles.deleteById(new WorkspaceTeamRoleId(teamId, roleId));
  }

  @Override
  public List<UUID> findRoleIdsByTeamId(final UUID teamId) {
    return teamRoles.findAllByWorkspaceTeamId(teamId).stream()
        .map(WorkspaceTeamRoleEntity::getWorkspaceRoleId)
        .toList();
  }

  // TD-PERF-030: one findAllByWorkspaceTeamIdIn call for every team in teamIds, instead of one
  // findAllByWorkspaceTeamId per team — see WorkspaceTeamRepository#findRoleIdsByTeamIds' own
  // Javadoc for the N+1 this closes.
  @Override
  public Map<UUID, List<UUID>> findRoleIdsByTeamIds(final Collection<UUID> teamIds) {
    final Map<UUID, List<UUID>> roleIdsByTeamId = new LinkedHashMap<>();
    teamIds.forEach(teamId -> roleIdsByTeamId.put(teamId, new ArrayList<>()));
    teamRoles
        .findAllByWorkspaceTeamIdIn(List.copyOf(teamIds))
        .forEach(
            entity ->
                roleIdsByTeamId.get(entity.getWorkspaceTeamId()).add(entity.getWorkspaceRoleId()));
    return roleIdsByTeamId;
  }

  @Override
  public Optional<UUID> findTeamIdForRoleInWorkspace(final UUID workspaceId, final UUID roleId) {
    final List<UUID> teamIds = teamIdsFor(workspaceId);
    return teamRoles.findAllByWorkspaceRoleIdAndWorkspaceTeamIdIn(roleId, teamIds).stream()
        .findFirst()
        .map(WorkspaceTeamRoleEntity::getWorkspaceTeamId);
  }

  @Override
  public Set<UUID> findAllGroupedRoleIdsForWorkspace(final UUID workspaceId) {
    final List<UUID> teamIds = teamIdsFor(workspaceId);
    return teamRoles.findAllByWorkspaceTeamIdIn(teamIds).stream()
        .map(WorkspaceTeamRoleEntity::getWorkspaceRoleId)
        .collect(Collectors.toSet());
  }

  @Override
  public boolean isRoleGroupedInAnyOtherTeam(final UUID roleId, final UUID excludedTeamId) {
    return teamRoles.existsByWorkspaceRoleIdAndWorkspaceTeamIdNot(roleId, excludedTeamId);
  }

  private List<UUID> teamIdsFor(final UUID workspaceId) {
    return teams.findAllByWorkspaceId(workspaceId).stream()
        .map(WorkspaceTeamEntity::getId)
        .toList();
  }

  private static WorkspaceTeam toDomain(final WorkspaceTeamEntity entity) {
    return WorkspaceTeam.reconstitute(
        entity.getId(), entity.getWorkspaceId(), entity.getName(), entity.getCreatedAt());
  }
}
