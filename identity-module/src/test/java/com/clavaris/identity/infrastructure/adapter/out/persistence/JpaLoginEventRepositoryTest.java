package com.clavaris.identity.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.application.usecase.getloginactivityforaccount.LoginActivityDay;
import com.clavaris.identity.application.usecase.recordloginevent.LoginEventRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.LoginEvent;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * test-strategy.md §2: a real-Postgres integration test for the {@code LoginEvent} adapter — same
 * hand-assembled {@code @SpringBootTest} pattern as {@code JpaSessionRepositoryTest} (see that
 * test's own Javadoc for why: Spring Boot 4.1 dropped {@code @DataJpaTest} entirely). Proves {@link
 * LoginEventEntity}'s own constructor/getters genuinely round-trip against the real migrated {@code
 * login_events} schema and its {@code accounts} foreign key — nothing in main code ever calls this
 * entity's getters directly ({@code countsByDaySince} reads a native-query projection instead), so
 * this is the only place they are exercised at all.
 */
@SpringBootTest(classes = JpaLoginEventRepositoryTest.TestConfig.class)
@Testcontainers
@Transactional
class JpaLoginEventRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private LoginEventRepository repository;
  @Autowired private SpringDataLoginEventJpaRepository events;
  @Autowired private JdbcTemplate jdbcTemplate;

  private AccountId accountId;
  private OrganizationId organizationId;

  // login_events.account_id carries a real FK to accounts — a minimal, direct row insert (not the
  // full AccountRepository, out of scope for this test's own TestConfig) is enough to satisfy it.
  @BeforeEach
  void seedAnAccount() {
    accountId = new AccountId(UUID.randomUUID());
    organizationId = new OrganizationId(UUID.randomUUID());
    jdbcTemplate.update(
        "insert into accounts (id, organization_id, email, status, created_at) "
            + "values (?, ?, ?, 'ACTIVE', now())",
        accountId.value(),
        organizationId.value(),
        "login-event-owner-" + accountId.value() + "@example.com");
  }

  @Test
  void insertPersistsEveryFieldCorrectly() {
    LoginEvent event = LoginEvent.occurNow(accountId, organizationId);

    repository.insert(event);

    Optional<LoginEventEntity> found = events.findById(event.id());
    assertThat(found).isPresent();
    assertThat(found.get().getId()).isEqualTo(event.id());
    assertThat(found.get().getAccountId()).isEqualTo(accountId.value());
    assertThat(found.get().getOrganizationId()).isEqualTo(organizationId.value());
    assertThat(found.get().getOccurredAt())
        .isCloseTo(
            event.occurredAt(), org.assertj.core.api.Assertions.within(5, ChronoUnit.MILLIS));
  }

  @Test
  void countsByDaySinceAggregatesOnlyThisAccountsEventsWithinTheWindow() {
    Instant now = Instant.now();
    repository.insert(new LoginEvent(UUID.randomUUID(), accountId, organizationId, now));
    repository.insert(new LoginEvent(UUID.randomUUID(), accountId, organizationId, now));
    // Outside the window — must not be counted.
    repository.insert(
        new LoginEvent(
            UUID.randomUUID(), accountId, organizationId, now.minus(10, ChronoUnit.DAYS)));
    // A different Account's own event, same day — must not leak into this Account's own count.
    AccountId otherAccountId = new AccountId(UUID.randomUUID());
    jdbcTemplate.update(
        "insert into accounts (id, organization_id, email, status, created_at) "
            + "values (?, ?, ?, 'ACTIVE', now())",
        otherAccountId.value(),
        organizationId.value(),
        "other-" + otherAccountId.value() + "@example.com");
    repository.insert(new LoginEvent(UUID.randomUUID(), otherAccountId, organizationId, now));

    java.util.List<LoginActivityDay> days =
        repository.countsByDaySince(accountId, now.minus(1, ChronoUnit.DAYS));

    assertThat(days).hasSize(1);
    assertThat(days.get(0).count()).isEqualTo(2);
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = SpringDataLoginEventJpaRepository.class,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = SpringDataLoginEventJpaRepository.class))
  @Import(JpaLoginEventRepository.class)
  static class TestConfig {}
}
