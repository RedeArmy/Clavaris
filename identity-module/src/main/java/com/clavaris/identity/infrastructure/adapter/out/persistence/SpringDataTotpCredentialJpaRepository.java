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

  // A JPQL delete runs now. The derived deleteBy only schedules a remove, which Hibernate flushes
  // after the inserts of the same transaction, so replacing an abandoned enrolment would hit
  // UNIQUE (account_id).
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("delete from TotpCredentialEntity c where c.accountId = :accountId")
  int deleteByAccountId(@Param("accountId") UUID accountId);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "delete from TotpCredentialEntity c where c.accountId = :accountId"
          + " and c.confirmedAt is null")
  int deleteUnconfirmedByAccountId(@Param("accountId") UUID accountId);

  // One statement, so the comparison and the write cannot be separated by another request.
  @Modifying
  @Query(
      "update TotpCredentialEntity c set c.lastUsedStep = :step"
          + " where c.accountId = :accountId and c.lastUsedStep < :step")
  int spendStep(@Param("accountId") UUID accountId, @Param("step") long step);
}
