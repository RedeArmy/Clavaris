package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataPendingSocialLinkJpaRepository
    extends JpaRepository<PendingSocialLinkEntity, UUID> {

  Optional<PendingSocialLinkEntity> findByConfirmationTokenHash(String confirmationTokenHash);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update PendingSocialLinkEntity l set l.consumedAt = :now "
          + "where l.accountId = :accountId and l.consumedAt is null and l.expiresAt > :now")
  int invalidateAllActiveForAccount(@Param("accountId") UUID accountId, @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update PendingSocialLinkEntity l set l.consumedAt = :consumedAt "
          + "where l.id = :linkId and l.consumedAt is null and l.expiresAt > :consumedAt")
  int consumeIfActive(@Param("linkId") UUID linkId, @Param("consumedAt") Instant consumedAt);
}
