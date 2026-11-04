package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataTotpRecoveryCodeJpaRepository
    extends JpaRepository<TotpRecoveryCodeEntity, UUID> {

  long countByAccountIdAndUsedAtIsNull(UUID accountId);

  @Modifying
  int deleteByAccountId(UUID accountId);

  @Modifying
  @Query(
      "update TotpRecoveryCodeEntity c set c.usedAt = :now"
          + " where c.accountId = :accountId and c.codeHash = :codeHash and c.usedAt is null")
  int consume(
      @Param("accountId") UUID accountId,
      @Param("codeHash") String codeHash,
      @Param("now") Instant now);
}
