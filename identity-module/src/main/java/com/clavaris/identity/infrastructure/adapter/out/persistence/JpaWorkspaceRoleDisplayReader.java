package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.identity.application.usecase.listaccountsfororganization.WorkspaceRoleDisplay;
import com.clavaris.identity.application.usecase.listaccountsfororganization.WorkspaceRoleDisplayReader;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

/**
 * Implements {@link WorkspaceRoleDisplayReader} against organization-module's own {@code
 * workspace_memberships}/{@code workspace_roles} tables via this module's own read-only projection
 * entities — see {@link WorkspaceMembershipReadEntity}'s own Javadoc for the data-contract
 * reasoning. Two plain queries, not a JPA join, same "resolve ids first, then look up the second
 * table by them" shape {@code JpaWorkspaceTeamRepository} (organization-module) already establishes
 * for an identical no-relationship-mapping situation.
 */
@Repository
class JpaWorkspaceRoleDisplayReader implements WorkspaceRoleDisplayReader {

  private final SpringDataWorkspaceMembershipReadJpaRepository memberships;
  private final SpringDataWorkspaceRoleReadJpaRepository roles;

  /* package */ JpaWorkspaceRoleDisplayReader(
      final SpringDataWorkspaceMembershipReadJpaRepository memberships,
      final SpringDataWorkspaceRoleReadJpaRepository roles) {
    this.memberships = memberships;
    this.roles = roles;
  }

  @Override
  public Map<UUID, WorkspaceRoleDisplay> findByAccountIds(final Collection<UUID> accountIds) {
    final List<WorkspaceMembershipReadEntity> found = memberships.findAllByAccountIdIn(accountIds);
    final List<UUID> roleIds =
        found.stream()
            .map(WorkspaceMembershipReadEntity::getRoleId)
            .filter(Objects::nonNull)
            .toList();
    final Map<UUID, String> roleNameById =
        roles.findAllById(roleIds).stream()
            .collect(
                Collectors.toMap(WorkspaceRoleReadEntity::getId, WorkspaceRoleReadEntity::getName));

    return found.stream()
        .collect(
            Collectors.toMap(
                WorkspaceMembershipReadEntity::getAccountId,
                membership ->
                    new WorkspaceRoleDisplay(
                        membership.getWorkspaceId(),
                        membership.getRoleId(),
                        membership.getRoleId() == null
                            ? null
                            : roleNameById.get(membership.getRoleId()))));
  }
}
