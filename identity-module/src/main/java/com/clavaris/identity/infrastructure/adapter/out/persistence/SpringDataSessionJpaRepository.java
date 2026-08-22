package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataSessionJpaRepository extends JpaRepository<SessionEntity, UUID> {

  // BR-ID-03's reuse-detection cascade — one bulk UPDATE, not a select-then-save-each loop; the
  // exact count of affected sessions is deliberately not surfaced to the caller
  // (JpaSessionRepository doesn't need it, and this method's own @Modifying default return type
  // already tracks it for anyone reading query logs).
  //
  // clearAutomatically/flushAutomatically: confirmed live — without these, a bulk JPQL UPDATE
  // writes straight to the DB but leaves the persistence context's first-level cache holding the
  // stale pre-update entity, so a findById() call right after this one (same EntityManager, e.g.
  // within the same @Transactional use case) returned the cached, not-yet-revoked Session instead
  // of re-querying. flushAutomatically ensures any pending unflushed writes are applied first, so
  // this bulk update can't race ahead of an in-flight save() in the same transaction.
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update SessionEntity s set s.revokedAt = :revokedAt "
          + "where s.accountId = :accountId and s.revokedAt is null")
  int revokeAllActiveForAccount(
      @Param("accountId") UUID accountId, @Param("revokedAt") Instant revokedAt);
}
