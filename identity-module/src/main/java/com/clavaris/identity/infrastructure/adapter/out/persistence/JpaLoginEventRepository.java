package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.identity.application.usecase.getloginactivityforaccount.LoginActivityDay;
import com.clavaris.identity.application.usecase.recordloginevent.LoginEventRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.LoginEvent;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements the outbound port; maps between {@code domain.model.LoginEvent} and {@link
 * LoginEventEntity}.
 */
@Repository
class JpaLoginEventRepository implements LoginEventRepository {

  private final SpringDataLoginEventJpaRepository events;
  private final EntityManager entityManager;

  /* package */ JpaLoginEventRepository(
      final SpringDataLoginEventJpaRepository events, final EntityManager entityManager) {
    this.events = events;
    this.entityManager = entityManager;
  }

  // TD-PERF-019: persist, not save — LoginEvent.occurNow always mints a brand-new row, one per
  // successful login, same "this aggregate has definitely never been persisted before" precedent
  // JpaAccountRepository#insert's own identical Javadoc establishes. No synchronous-flush need
  // here (unlike KnownDeviceRepository#insert): nothing downstream in the same request ever reads
  // this row back, and RecordLoginEventService's own try/catch already wraps this whole call.
  @Override
  @Transactional
  public void insert(final LoginEvent event) {
    entityManager.persist(
        new LoginEventEntity(
            event.id(),
            event.accountId().value(),
            event.organizationId().value(),
            event.occurredAt()));
  }

  // Real bug found live by JpaLoginEventRepositoryTest's own first run, not assumed: this
  // native-query ::date projection's row[0] comes back as a plain java.time.LocalDate on this
  // project's own pinned Hibernate/JDBC driver versions, not the legacy java.sql.Date a
  // ResultSet#getDate call would return — a real, previously-uncaught ClassCastException on every
  // single call, invisible to GetLoginActivityForAccountServiceTest's own mocked-repository-port
  // tests. Cast directly, not defended against the other shape too — pom.xml pins exact versions
  // for this whole reactor, so which type Hibernate returns here is a fixed fact for this stack,
  // not a scenario to hedge against (same "trust the locked dependency graph" posture every other
  // native-query row mapping in this codebase already takes).
  @Override
  public List<LoginActivityDay> countsByDaySince(final AccountId accountId, final Instant since) {
    return events.countsByDaySince(accountId.value(), since).stream()
        .map(row -> new LoginActivityDay((LocalDate) row[0], ((Number) row[1]).longValue()))
        .toList();
  }
}
