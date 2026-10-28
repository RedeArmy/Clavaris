package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataPendingPlatformSocialLinkJpaRepository
    extends JpaRepository<PendingPlatformSocialLinkEntity, UUID> {

  Optional<PendingPlatformSocialLinkEntity> findByConfirmationTokenHash(
      String confirmationTokenHash);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update PendingPlatformSocialLinkEntity l set l.consumedAt = :consumedAt "
          + "where l.id = :linkId and l.consumedAt is null and l.expiresAt > :consumedAt")
  int consumeIfActive(@Param("linkId") UUID linkId, @Param("consumedAt") Instant consumedAt);
}
