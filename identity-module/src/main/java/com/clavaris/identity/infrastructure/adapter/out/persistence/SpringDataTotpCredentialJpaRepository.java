package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataTotpCredentialJpaRepository extends JpaRepository<TotpCredentialEntity, UUID> {

  Optional<TotpCredentialEntity> findByAccountId(UUID accountId);

  boolean existsByAccountIdAndConfirmedAtIsNotNull(UUID accountId);

  @Modifying
  int deleteByAccountId(UUID accountId);

  // One statement, so the comparison and the write cannot be separated by another request.
  @Modifying
  @Query(
      "update TotpCredentialEntity c set c.lastUsedStep = :step"
          + " where c.accountId = :accountId and c.lastUsedStep < :step")
  int spendStep(@Param("accountId") UUID accountId, @Param("step") long step);
}
