package com.clavaris.organization.infrastructure.adapter.out.persistence;

import com.clavaris.common.domain.model.Page;
import com.clavaris.common.domain.model.PageRequest;
import com.clavaris.common.infrastructure.adapter.out.persistence.SpringDataPageMapper;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Implements the outbound port; maps between {@code domain.model.WorkspaceMembership} and {@link
 * WorkspaceMembershipEntity}.
 *
 * <p>PMD.TooManyMethods (TD-PERF-020/TD-PERF-021's own {@code findPageByWorkspaceId}/{@code
 * findAllByWorkspaceIds} pushed this past the default threshold): every method here backs a real,
 * distinct {@code WorkspaceMembershipRepository} port method this module's use cases actually need
 * — same "one port, several use cases" shape {@code AccountRepository}'s own identical suppression
 * documents, not a design smell to split up.
 */
@SuppressWarnings("PMD.TooManyMethods")
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
  public List<WorkspaceMembership> findAllByWorkspaceIds(final Collection<UUID> workspaceIds) {
    return memberships.findAllByWorkspaceIdIn(workspaceIds).stream().map(this::toDomain).toList();
  }

  // TD-PERF-020: newest-first, id as a tiebreaker — same reasoning JpaOrganizationRepository's own
  // identical findPageOwnedBy already documents.
  @Override
  public Page<WorkspaceMembership> findPageByWorkspaceId(
      final UUID workspaceId, final PageRequest pageRequest) {
    return SpringDataPageMapper.toPage(
        memberships.findAllByWorkspaceId(
            workspaceId,
            org.springframework.data.domain.PageRequest.of(
                pageRequest.page(),
                pageRequest.size(),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))),
        pageRequest,
        this::toDomain);
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
