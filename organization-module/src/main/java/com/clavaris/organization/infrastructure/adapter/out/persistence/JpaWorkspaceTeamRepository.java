package com.clavaris.organization.infrastructure.adapter.out.persistence;

import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamRepository;
import com.clavaris.organization.domain.model.WorkspaceTeam;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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

  /* package */ JpaWorkspaceTeamRepository(
      final SpringDataWorkspaceTeamJpaRepository teams,
      final SpringDataWorkspaceTeamRoleJpaRepository teamRoles) {
    this.teams = teams;
    this.teamRoles = teamRoles;
  }

  @Override
  public void save(final WorkspaceTeam team) {
    teams.save(
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
