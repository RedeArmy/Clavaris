package com.clavaris.organization.infrastructure.adapter.out.persistence;

import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Implements the outbound port; maps between {@code domain.model.WorkspaceMembership} and {@link
 * WorkspaceMembershipEntity}.
 */
@Repository
class JpaWorkspaceMembershipRepository implements WorkspaceMembershipRepository {

  private final SpringDataWorkspaceMembershipJpaRepository memberships;

  /* package */ JpaWorkspaceMembershipRepository(
      final SpringDataWorkspaceMembershipJpaRepository memberships) {
    this.memberships = memberships;
  }

  @Override
  public void save(final WorkspaceMembership membership) {
    memberships.save(
        new WorkspaceMembershipEntity(
            membership.id(),
            membership.workspaceId(),
            membership.accountId(),
            membership.role(),
            membership.createdAt()));
  }

  @Override
  public Optional<WorkspaceMembership> findByWorkspaceIdAndAccountId(
      final UUID workspaceId, final UUID accountId) {
    return memberships.findByWorkspaceIdAndAccountId(workspaceId, accountId).map(this::toDomain);
  }

  @Override
  public List<WorkspaceMembership> findAllByWorkspaceId(final UUID workspaceId) {
    return memberships.findAllByWorkspaceId(workspaceId).stream().map(this::toDomain).toList();
  }

  @Override
  public List<WorkspaceMembership> findAllByAccountId(final UUID accountId) {
    return memberships.findAllByAccountId(accountId).stream().map(this::toDomain).toList();
  }

  @Override
  public long countByWorkspaceIdAndRole(final UUID workspaceId, final WorkspaceRole role) {
    return memberships.countByWorkspaceIdAndRole(workspaceId, role);
  }

  @Override
  public void deleteById(final UUID membershipId) {
    memberships.deleteById(membershipId);
    // .flush() — same "must actually reach Postgres now, not deferred" reasoning as
    // JpaOrganizationRepository's own identical call.
    memberships.flush();
  }

  @Override
  public void deleteAllByAccountId(final UUID accountId) {
    memberships.deleteAllByAccountId(accountId);
    memberships.flush();
  }

  private WorkspaceMembership toDomain(final WorkspaceMembershipEntity entity) {
    return WorkspaceMembership.reconstitute(
        entity.getId(),
        entity.getWorkspaceId(),
        entity.getAccountId(),
        entity.getRole(),
        entity.getCreatedAt());
  }
}
