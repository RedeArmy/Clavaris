package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataPendingSessionClosureJpaRepository
    extends JpaRepository<PendingSessionClosureEntity, UUID> {

  // ON CONFLICT DO NOTHING: two failures for the same account, or two instances recording at once,
  // are one row, not a unique-violation that would fail the suspension this exists to protect.
  @Modifying
  @Query(
      value =
          "insert into pending_session_closures (account_id, recorded_at) "
              + "values (:accountId, :recordedAt) on conflict (account_id) do nothing",
      nativeQuery = true)
  void insertIfAbsent(@Param("accountId") UUID accountId, @Param("recordedAt") Instant recordedAt);

  @Query("select p.accountId from PendingSessionClosureEntity p order by p.recordedAt, p.accountId")
  List<UUID> oldest(Pageable page);

  @Modifying
  @Query("delete from PendingSessionClosureEntity p where p.accountId = :accountId")
  void deleteByAccount(@Param("accountId") UUID accountId);
}
