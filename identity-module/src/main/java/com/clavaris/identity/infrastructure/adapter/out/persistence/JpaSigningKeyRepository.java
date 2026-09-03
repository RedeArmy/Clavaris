package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.SigningKeyRepository;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SigningKey;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Implements the outbound port; maps between {@code domain.model.SigningKey} and {@link
 * SigningKeyEntity}.
 */
@Repository
class JpaSigningKeyRepository implements SigningKeyRepository {

  private final SpringDataSigningKeyJpaRepository signingKeys;
  private final JdbcTemplate jdbcTemplate;

  /* package */ JpaSigningKeyRepository(
      final SpringDataSigningKeyJpaRepository signingKeys, final JdbcTemplate jdbcTemplate) {
    this.signingKeys = signingKeys;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<SigningKey> findActive(final OrganizationId organizationId) {
    return signingKeys
        .findFirstByOrganizationIdAndRetiredAtIsNull(organizationId.value())
        .map(this::toDomain);
  }

  // See SigningKeyRepository#lockForRotation's own Javadoc for why this is a transaction-scoped
  // Postgres advisory lock, not a row-level SELECT ... FOR UPDATE. Plain JdbcTemplate, not a
  // Spring Data derived/native-@Query method — real bug found and fixed live getting there: a
  // void-returning native @Query without @Modifying is never actually sent to Postgres at all
  // (nothing ever consumes its result, the only thing that would trigger execution), and
  // @Modifying alone forces Hibernate through executeUpdate(), which PostgreSQL's own JDBC driver
  // rejects for a SELECT-shaped statement ("A result was returned when none was expected").
  // query(sql, RowCallbackHandler, args) genuinely executes it as the SELECT it is and discards
  // the single-column result — the lock's entire value is its side effect, not its return value.
  @Override
  public void lockForRotation(final OrganizationId organizationId) {
    jdbcTemplate.query(
        "SELECT pg_advisory_xact_lock(hashtext(?))",
        resultSet -> {
          /* side-effecting call — the lock itself is the point, not this row */
        },
        organizationId.value().toString());
  }

  @Override
  public List<SigningKey> findActiveAndRetiredSince(
      final OrganizationId organizationId, final Instant retiredAfter) {
    return signingKeys.findActiveAndRetiredSince(organizationId.value(), retiredAfter).stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  public Optional<SigningKey> findByKid(final OrganizationId organizationId, final String kid) {
    return signingKeys
        .findFirstByOrganizationIdAndKid(organizationId.value(), kid)
        .map(this::toDomain);
  }

  // saveAndFlush, not save — real bug found and fixed live (SDE-III review, 2026-09-03): Hibernate
  // orders its own flush queue by operation type (every INSERT before every UPDATE, regardless of
  // the order save() was actually called in), not call order — ActivateSigningKeyForOrganization
  // Service's retire-then-activate sequence calls save() on the retiring old key first, then the
  // new key, but an unflushed session would still send the new key's INSERT to Postgres before the
  // old key's UPDATE (retired_at), transiently violating ux_signing_keys_organization_id_active
  // (migration V20260903090000) even for a single, non-concurrent rotation — confirmed live, a
  // real integration test caught this exact ordering, not a hypothetical. Flushing after every
  // save keeps each one's own statement ordering exactly what the Java call order already implies.
  @Override
  public void save(final SigningKey signingKey) {
    signingKeys.saveAndFlush(
        new SigningKeyEntity(
            signingKey.id(),
            signingKey.organizationId().value(),
            signingKey.kid(),
            signingKey.algorithm(),
            signingKey.activeFrom(),
            signingKey.retiredAt().orElse(null)));
  }

  @Override
  public void deleteAllByOrganizationId(final OrganizationId organizationId) {
    signingKeys.deleteAllByOrganizationId(organizationId.value());
    signingKeys.flush();
  }

  private SigningKey toDomain(final SigningKeyEntity entity) {
    return SigningKey.reconstitute(
        entity.getId(),
        new OrganizationId(entity.getOrganizationId()),
        entity.getKid(),
        entity.getAlgorithm(),
        entity.getActiveFrom(),
        entity.getRetiredAt());
  }
}
