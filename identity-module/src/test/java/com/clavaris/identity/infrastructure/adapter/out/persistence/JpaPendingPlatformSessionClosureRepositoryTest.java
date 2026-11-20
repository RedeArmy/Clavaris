package com.clavaris.identity.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.application.usecase.deferplatformsessionclosure.PendingPlatformSessionClosureRepository;
import com.clavaris.identity.domain.model.PlatformAccountId;
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
@SpringBootTest(classes = JpaPendingPlatformSessionClosureRepositoryTest.TestConfig.class)
@Testcontainers
@Transactional
class JpaPendingPlatformSessionClosureRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private PendingPlatformSessionClosureRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void recordsAnAccountOnceWhateverTheNumberOfFailures() {
    PlatformAccountId account = new PlatformAccountId(UUID.randomUUID());
    Instant first = Instant.parse("2026-11-18T10:00:00Z");

    repository.add(account, first);
    repository.add(account, first.plusSeconds(60));

    assertThat(repository.oldest(10)).containsExactly(account);
    assertThat(
            jdbcTemplate.queryForObject(
                "select recorded_at from pending_platform_session_closures where platform_account_id = ?",
                java.sql.Timestamp.class,
                account.value()))
        .as("the first record stays")
        .isEqualTo(java.sql.Timestamp.from(first));
  }

  @Test
  void returnsTheOldestFirstAndAtMostTheLimit() {
    PlatformAccountId older = new PlatformAccountId(UUID.randomUUID());
    PlatformAccountId newer = new PlatformAccountId(UUID.randomUUID());
    PlatformAccountId newest = new PlatformAccountId(UUID.randomUUID());
    Instant base = Instant.parse("2026-11-18T10:00:00Z");
    repository.add(newest, base.plusSeconds(20));
    repository.add(older, base);
    repository.add(newer, base.plusSeconds(10));

    assertThat(repository.oldest(2)).containsExactly(older, newer);
    assertThat(repository.oldest(10)).containsExactly(older, newer, newest);
  }

  // TD-PROC-036: the backlog the gauge reports.
  @Test
  void countsHowManyAccountsAreWaiting() {
    assertThat(repository.count()).isZero();
    repository.add(new PlatformAccountId(UUID.randomUUID()), Instant.now());
    repository.add(new PlatformAccountId(UUID.randomUUID()), Instant.now());

    assertThat(repository.count()).isEqualTo(2);
  }

  @Test
  void removesOnlyTheNamedAccountAndToleratesOneThatIsNotThere() {
    PlatformAccountId kept = new PlatformAccountId(UUID.randomUUID());
    PlatformAccountId removed = new PlatformAccountId(UUID.randomUUID());
    repository.add(kept, Instant.now());
    repository.add(removed, Instant.now());

    repository.remove(removed);
    repository.remove(removed);

    assertThat(repository.oldest(10)).containsExactly(kept);
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = SpringDataPendingPlatformSessionClosureJpaRepository.class,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = SpringDataPendingPlatformSessionClosureJpaRepository.class))
  @Import(JpaPendingPlatformSessionClosureRepository.class)
  static class TestConfig {}
}
