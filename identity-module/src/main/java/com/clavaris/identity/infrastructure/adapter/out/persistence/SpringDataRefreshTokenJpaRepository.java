package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataRefreshTokenJpaRepository extends JpaRepository<RefreshTokenEntity, UUID> {

  Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

  // BR-ID-03's reuse-detection cascade — one bulk UPDATE, same shape (and same
  // clearAutomatically/flushAutomatically reasoning, confirmed live) as
  // SpringDataSessionJpaRepository's own equivalent.
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update RefreshTokenEntity r set r.revokedAt = :revokedAt "
          + "where r.accountId = :accountId and r.revokedAt is null")
  int revokeAllActiveForAccount(
      @Param("accountId") UUID accountId, @Param("revokedAt") Instant revokedAt);
}
