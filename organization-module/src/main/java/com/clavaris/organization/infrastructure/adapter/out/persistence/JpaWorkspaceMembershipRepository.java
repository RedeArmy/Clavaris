package com.clavaris.organization.infrastructure.adapter.out.persistence;

import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Implements the outbound port; maps between {@code domain.model.WorkspaceMembership} and {@link
 * WorkspaceMembershipEntity}.
 */
@Repository
class JpaWorkspaceMembershipRepository implements WorkspaceMembershipRepository {

  private final SpringDataWorkspaceMembershipJpaRepository memberships;
  private final JdbcTemplate jdbcTemplate;

  /* package */ JpaWorkspaceMembershipRepository(
      final SpringDataWorkspaceMembershipJpaRepository memberships,
      final JdbcTemplate jdbcTemplate) {
    this.memberships = memberships;
    this.jdbcTemplate = jdbcTemplate;
  }

  // See WorkspaceMembershipRepository#lockForRoleChange's own Javadoc. Plain JdbcTemplate, not a
  // Spring Data native @Query method — a void-returning native query without @Modifying is never
  // actually sent to Postgres (nothing consumes its result), and @Modifying alone forces
  // Hibernate through executeUpdate(), which PostgreSQL's own JDBC driver rejects for a
  // SELECT-shaped statement ("A result was returned when none was expected") — both confirmed
  // live while building this exact pattern for SigningKeyRepository#lockForRotation.
  // query(sql, RowCallbackHandler, args) genuinely executes it as the SELECT it is and discards
  // the single-column result — the lock's entire value is its side effect, not its return value.
  @Override
  public void lockForRoleChange(final UUID workspaceId) {
    jdbcTemplate.query(
        "SELECT pg_advisory_xact_lock(hashtext(?))",
        resultSet -> {
          /* side-effecting call — the lock itself is the point, not this row */
        },
        workspaceId.toString());
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
