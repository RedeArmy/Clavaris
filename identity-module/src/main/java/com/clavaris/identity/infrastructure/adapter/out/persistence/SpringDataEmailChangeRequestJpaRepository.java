package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataEmailChangeRequestJpaRepository
    extends JpaRepository<EmailChangeRequestEntity, UUID> {

  Optional<EmailChangeRequestEntity> findByTokenHash(String tokenHash);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("delete from EmailChangeRequestEntity r where r.accountId = :accountId")
  void deleteAllByAccountId(@Param("accountId") UUID accountId);

  // Same single-row conditional UPDATE as SpringDataVerificationTokenJpaRepository#consumeIfActive.
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update EmailChangeRequestEntity r set r.consumedAt = :consumedAt "
          + "where r.id = :requestId and r.consumedAt is null and r.expiresAt > :consumedAt")
  int consumeIfActive(@Param("requestId") UUID requestId, @Param("consumedAt") Instant consumedAt);
}
