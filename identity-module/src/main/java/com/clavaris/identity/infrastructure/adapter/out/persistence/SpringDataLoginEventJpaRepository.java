package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataLoginEventJpaRepository extends JpaRepository<LoginEventEntity, UUID> {

  // TD-FUT-034: day-bucketed counts, the one real query shape this table exists for — a native
  // query, same "aggregation this codebase's own JPQL dialect can't portably express" precedent
  // SpringDataAccountJpaRepository's own TD-PERF-001 native queries already establish. Bucketed
  // by UTC calendar day explicitly (AT TIME ZONE 'UTC'), not the connection's session zone, so it
  // always agrees with the UTC grid PlatformAccountDetailController draws.
  @Query(
      value =
          "SELECT (occurred_at AT TIME ZONE 'UTC')::date AS day, COUNT(*) AS login_count FROM login_events "
              + "WHERE account_id = :accountId AND occurred_at >= :since "
              + "GROUP BY day ORDER BY day",
      nativeQuery = true)
  List<Object[]> countsByDaySince(
      @Param("accountId") UUID accountId, @Param("since") Instant since);
}
