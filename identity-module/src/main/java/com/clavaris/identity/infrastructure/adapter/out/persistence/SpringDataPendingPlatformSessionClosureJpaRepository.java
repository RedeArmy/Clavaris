package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataPendingPlatformSessionClosureJpaRepository
    extends JpaRepository<PendingPlatformSessionClosureEntity, UUID> {

  // ON CONFLICT DO NOTHING: two failures for the same account are one row, never a unique
  // violation that would fail the suspension this exists to protect.
  @Modifying
  @Query(
      value =
          "insert into pending_platform_session_closures (platform_account_id, recorded_at) "
              + "values (:platformAccountId, :recordedAt) "
              + "on conflict (platform_account_id) do nothing",
      nativeQuery = true)
  void insertIfAbsent(
      @Param("platformAccountId") UUID platformAccountId, @Param("recordedAt") Instant recordedAt);

  @Query(
      "select p.platformAccountId from PendingPlatformSessionClosureEntity p "
          + "order by p.recordedAt, p.platformAccountId")
  List<UUID> oldest(Pageable page);

  @Modifying
  @Query(
      "delete from PendingPlatformSessionClosureEntity p "
          + "where p.platformAccountId = :platformAccountId")
  void deleteByAccount(@Param("platformAccountId") UUID platformAccountId);
}
