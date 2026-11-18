package com.clavaris.identity.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.application.usecase.deferaccountsessionclosure.PendingSessionClosureRepository;
import com.clavaris.identity.domain.model.AccountId;
import java.time.Instant;
import java.util.UUID;
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

// TD-ARCH-057: a real table, a real ON CONFLICT, a real ordering.
@SpringBootTest(classes = JpaPendingSessionClosureRepositoryTest.TestConfig.class)
@Testcontainers
@Transactional
class JpaPendingSessionClosureRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private PendingSessionClosureRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void recordsAnAccountOnceWhateverTheNumberOfFailures() {
    AccountId account = new AccountId(UUID.randomUUID());
    Instant first = Instant.parse("2026-11-18T10:00:00Z");

    repository.record(account, first);
    repository.record(account, first.plusSeconds(60));

    assertThat(repository.oldest(10)).containsExactly(account);
    assertThat(
            jdbcTemplate.queryForObject(
                "select recorded_at from pending_session_closures where account_id = ?",
                java.sql.Timestamp.class,
                account.value()))
        .as("the first record stays")
        .isEqualTo(java.sql.Timestamp.from(first));
  }

  @Test
  void returnsTheOldestFirstAndAtMostTheLimit() {
    AccountId older = new AccountId(UUID.randomUUID());
    AccountId newer = new AccountId(UUID.randomUUID());
    AccountId newest = new AccountId(UUID.randomUUID());
    Instant base = Instant.parse("2026-11-18T10:00:00Z");
    repository.record(newest, base.plusSeconds(20));
    repository.record(older, base);
    repository.record(newer, base.plusSeconds(10));

    assertThat(repository.oldest(2)).containsExactly(older, newer);
    assertThat(repository.oldest(10)).containsExactly(older, newer, newest);
  }

  @Test
  void removesOnlyTheNamedAccountAndToleratesOneThatIsNotThere() {
    AccountId kept = new AccountId(UUID.randomUUID());
    AccountId removed = new AccountId(UUID.randomUUID());
    repository.record(kept, Instant.now());
    repository.record(removed, Instant.now());

    repository.remove(removed);
    repository.remove(removed);

    assertThat(repository.oldest(10)).containsExactly(kept);
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = SpringDataPendingSessionClosureJpaRepository.class,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = SpringDataPendingSessionClosureJpaRepository.class))
  @Import(JpaPendingSessionClosureRepository.class)
  static class TestConfig {}
}
