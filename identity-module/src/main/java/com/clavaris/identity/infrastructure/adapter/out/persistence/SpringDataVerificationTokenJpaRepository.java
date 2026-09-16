package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataVerificationTokenJpaRepository
    extends JpaRepository<VerificationTokenEntity, UUID> {

  Optional<VerificationTokenEntity> findByTokenHash(String tokenHash);

  // VerificationTokenRepository#consumeIfActive's own Javadoc — the row-count returned by this
  // single-row conditional UPDATE is the whole fix: 1 if this call consumed it, 0 if another call
  // already had, or it had already expired. Same shape as
  // SpringDataRefreshTokenJpaRepository#revokeIfActive.
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update VerificationTokenEntity t set t.consumedAt = :consumedAt "
          + "where t.id = :tokenId and t.consumedAt is null and t.expiresAt > :consumedAt")
  int consumeIfActive(@Param("tokenId") UUID tokenId, @Param("consumedAt") Instant consumedAt);
}
