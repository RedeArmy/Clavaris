package com.clavaris.identity.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * test-strategy.md §2: a real-Postgres integration test for the {@code VerificationToken} adapter —
 * proves {@code toDomain()} really calls {@code VerificationToken.reconstitute(...)}, that {@code
 * token_hash} round-trips exactly, and that {@code type} survives the {@code String}↔enum
 * conversion this entity deliberately does (see {@link VerificationTokenEntity}'s own Javadoc).
 */
@SpringBootTest(classes = JpaVerificationTokenRepositoryTest.TestConfig.class)
@Testcontainers
@Transactional
class JpaVerificationTokenRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private VerificationTokenRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  private AccountId accountId;

  @BeforeEach
  void seedAnAccount() {
    accountId = new AccountId(UUID.randomUUID());
    // BR-ID-02's deferred constraint trigger ("never zero auth methods") only ever fires at real
    // COMMIT — every other test in this class runs inside the class-level @Transactional's
    // rollback-only wrapping, which never commits and so never actually evaluates it, but
    // concurrentConsumeIfActiveCallsAgainstTheSameTokenNeverBothWin_realConcurrencyProof below opts
    // out of that wrapping (Propagation.NOT_SUPPORTED) to prove real cross-transaction concurrency,
    // and genuinely commits. Both inserts must land in the same transaction, or the account-only
    // insert already violates BR-ID-02 before the credential insert below ever runs. Same pattern
    // JpaRefreshTokenRepositoryTest's own identical seed method establishes.
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              jdbcTemplate.update(
                  "insert into accounts (id, organization_id, email, status, created_at) "
                      + "values (?, ?, ?, 'ACTIVE', now())",
                  accountId.value(),
                  UUID.randomUUID(),
                  "verification-owner-" + accountId.value() + "@example.com");
              jdbcTemplate.update(
                  "insert into password_credentials (account_id, password_hash) values (?, ?)",
                  accountId.value(),
                  "not-a-real-hash-this-suite-never-logs-in");
            });
  }

  @Test
  void savesAndFindsByTokenHash_reconstituteKeepsTheRealPersistedIdAndType() {
    VerificationToken token =
        VerificationToken.issue(
            accountId,
            VerificationTokenType.EMAIL_VERIFICATION,
            "a-hash",
            Instant.now().plusSeconds(3600));

    repository.save(token);
    Optional<VerificationToken> found = repository.findByTokenHash("a-hash");

    assertThat(found).isPresent();
    assertThat(found.get().id()).isEqualTo(token.id());
    assertThat(found.get().accountId()).isEqualTo(accountId);
    assertThat(found.get().type()).isEqualTo(VerificationTokenType.EMAIL_VERIFICATION);
    assertThat(found.get().consumedAt()).isEmpty();
  }

  @Test
  void findByTokenHashIsEmptyForAnUnknownHash() {
    assertThat(repository.findByTokenHash("never-issued")).isEmpty();
  }

  @Test
  void savePersistsAConsumedPasswordResetTokenCorrectly() {
    VerificationToken token =
        VerificationToken.issue(
            accountId,
            VerificationTokenType.PASSWORD_RESET,
            "a-hash",
            Instant.now().plusSeconds(3600));
    token.consume();

    repository.save(token);

    VerificationToken found = repository.findByTokenHash("a-hash").orElseThrow();
    assertThat(found.type()).isEqualTo(VerificationTokenType.PASSWORD_RESET);
    assertThat(found.consumedAt()).isPresent();
    assertThat(found.isActive()).isFalse();
  }

  // VerificationTokenRepository#consumeIfActive's own Javadoc (SDE-III review, 2026-09-15) — the
  // whole fix is that a second call against an already-consumed row reports false, not true. A
  // single-threaded, deterministic proof of that row-count-backed semantic; real concurrent-caller
  // behavior is proved separately below, since sequential calls in one transaction can't show
  // whether a genuinely concurrent second caller is forced to re-check the WHERE clause. Mirrors
  // JpaRefreshTokenRepositoryTest#revokeIfActiveReturnsTrueOnceThenFalseForTheSameToken exactly.
  @Test
  void consumeIfActiveReturnsTrueOnceThenFalseForTheSameToken() {
    VerificationToken token =
        VerificationToken.issue(
            accountId,
            VerificationTokenType.PASSWORD_RESET,
            "race-hash",
            Instant.now().plusSeconds(3600));
    repository.save(token);

    boolean firstCall = repository.consumeIfActive(token.id(), Instant.now());
    boolean secondCall = repository.consumeIfActive(token.id(), Instant.now());

    assertThat(firstCall).as("the only call against a still-active token must win").isTrue();
    assertThat(secondCall)
        .as("a second call against the now-already-consumed row must report it lost, not repeat")
        .isFalse();
    assertThat(repository.findByTokenHash("race-hash").orElseThrow().isActive()).isFalse();
  }

  // BR-ID-04's real TOCTOU-fix proof: ConfirmPasswordResetServiceTest already proves the service
  // layer treats a lost race as an invalid token using a mocked repository, which can only show the
  // *service* reacts correctly to a stubbed false — it cannot prove PostgreSQL itself actually
  // forces the loser of two genuinely concurrent transactions to observe that false. This test
  // proves that, against a real database, the same way
  // JpaRefreshTokenRepositoryTest#concurrentRevokeIfActiveCallsAgainstTheSameTokenNeverBothWin_realConcurrencyProof
  // does for its own identical race class — two separate threads, two separate transactions, two
  // separate connections, racing the exact same row.
  //
  // Propagation.NOT_SUPPORTED: escapes this class's own @Transactional (which would otherwise wrap
  // this method's @BeforeEach-seeded row in an uncommitted outer transaction invisible to the two
  // genuinely separate transactions below) — the seeded account here commits for real, exactly like
  // JpaRefreshTokenRepositoryTest's own un-annotated equivalent relies on.
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void concurrentConsumeIfActiveCallsAgainstTheSameTokenNeverBothWin_realConcurrencyProof() {
    VerificationToken token =
        VerificationToken.issue(
            accountId,
            VerificationTokenType.PASSWORD_RESET,
            "concurrent-race-hash",
            Instant.now().plusSeconds(3600));
    repository.save(token);
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

    CompletableFuture<Boolean> callA =
        CompletableFuture.supplyAsync(
            () ->
                transactionTemplate.execute(
                    status -> repository.consumeIfActive(token.id(), Instant.now())));
    CompletableFuture<Boolean> callB =
        CompletableFuture.supplyAsync(
            () ->
                transactionTemplate.execute(
                    status -> repository.consumeIfActive(token.id(), Instant.now())));

    boolean wonByA = Boolean.TRUE.equals(callA.join());
    boolean wonByB = Boolean.TRUE.equals(callB.join());

    assertThat(wonByA ^ wonByB)
        .as("exactly one of two concurrent callers may ever consume the same active token")
        .isTrue();
    assertThat(repository.findByTokenHash("concurrent-race-hash").orElseThrow().isActive())
        .isFalse();
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = SpringDataVerificationTokenJpaRepository.class,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = SpringDataVerificationTokenJpaRepository.class))
  @Import(JpaVerificationTokenRepository.class)
  static class TestConfig {}
}
