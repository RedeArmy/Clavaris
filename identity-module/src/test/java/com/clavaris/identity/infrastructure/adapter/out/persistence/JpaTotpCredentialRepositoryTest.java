package com.clavaris.identity.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.application.usecase.starttotpenrollment.TotpCredentialRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.TotpCredential;
import java.time.Instant;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ADR-0040: the Postgres adapter of {@link TotpCredentialRepository}. Not {@code @Transactional}:
 * the enrolment restart deletes and inserts in one real transaction, and only a real commit shows
 * whether the {@code UNIQUE (account_id)} constraint sees the insert before the delete.
 */
@SpringBootTest(classes = JpaTotpCredentialRepositoryTest.TestConfig.class)
@Testcontainers
class JpaTotpCredentialRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private TotpCredentialRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  private AccountId accountId;
  private OrganizationId organizationId;

  @BeforeEach
  void seedAnAccount() {
    accountId = new AccountId(UUID.randomUUID());
    organizationId = new OrganizationId(UUID.randomUUID());
    // BR-ID-02's deferred trigger: an account needs a credential in the same transaction.
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              jdbcTemplate.update(
                  "insert into accounts (id, organization_id, email, status, created_at) "
                      + "values (?, ?, ?, 'ACTIVE', now())",
                  accountId.value(),
                  organizationId.value(),
                  "totp-owner-" + accountId.value() + "@example.com");
              jdbcTemplate.update(
                  "insert into password_credentials (account_id, password_hash) values (?, ?)",
                  accountId.value(),
                  "not-a-real-hash-this-suite-never-logs-in");
            });
  }

  @Test
  void anUnconfirmedCredentialCanBeReplacedInTheSameTransaction() {
    final TotpCredential abandoned = TotpCredential.pending(accountId, organizationId, "old");
    repository.save(abandoned);
    final TotpCredential replacement = TotpCredential.pending(accountId, organizationId, "new");

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              repository.deleteByAccountId(accountId);
              repository.save(replacement);
            });

    assertThat(repository.findByAccountId(accountId).orElseThrow().id())
        .isEqualTo(replacement.id());
  }

  @Test
  void deletingUnconfirmedLeavesAConfirmedCredentialAlone() {
    final TotpCredential confirmed =
        TotpCredential.reconstitute(
            UUID.randomUUID(),
            accountId,
            organizationId,
            "secret",
            Instant.now(),
            5,
            Instant.now());
    repository.save(confirmed);

    assertThat(inTransaction(() -> repository.deleteUnconfirmedByAccountId(accountId))).isFalse();

    assertThat(repository.existsConfirmed(accountId)).isTrue();
  }

  @Test
  void deletingUnconfirmedRemovesWhatAnAbandonedSetupLeft() {
    repository.save(TotpCredential.pending(accountId, organizationId, "abandoned"));

    assertThat(inTransaction(() -> repository.deleteUnconfirmedByAccountId(accountId))).isTrue();

    assertThat(repository.findByAccountId(accountId)).isEmpty();
  }

  // The adapter's deletes are modifying queries, which need the transaction every use case opens.
  private <T> T inTransaction(final java.util.function.Supplier<T> work) {
    return new TransactionTemplate(transactionManager).execute(status -> work.get());
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = SpringDataTotpCredentialJpaRepository.class,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = SpringDataTotpCredentialJpaRepository.class))
  @Import(JpaTotpCredentialRepository.class)
  static class TestConfig {}
}
