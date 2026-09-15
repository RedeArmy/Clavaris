package com.clavaris.identity.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.application.usecase.issuerefreshtoken.RefreshTokenRepository;
import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.RefreshToken;
import com.clavaris.identity.domain.model.Session;
import java.time.Instant;
import java.util.List;
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
 * test-strategy.md §2: a real-Postgres integration test for the {@code RefreshToken} adapter —
 * proves {@code toDomain()} really calls {@code RefreshToken.reconstitute(...)}, that {@code
 * token_hash} round-trips exactly (BR-ID-03's own lookup key), and that {@code
 * revokeAllActiveForAccount} — the reuse-detection cascade's actual persistence step — is a real,
 * correctly-scoped bulk update against the real migrated schema, not just correct against a mock.
 */
@SpringBootTest(classes = JpaRefreshTokenRepositoryTest.TestConfig.class)
@Testcontainers
@Transactional
class JpaRefreshTokenRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private RefreshTokenRepository repository;
  @Autowired private SessionRepository sessions;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  private AccountId accountId;
  private Session session;

  @BeforeEach
  void seedAnAccountAndSession() {
    accountId = new AccountId(UUID.randomUUID());
    // BR-ID-02's deferred constraint trigger ("never zero auth methods") only ever fires at real
    // COMMIT — every other test in this class runs inside the class-level @Transactional's
    // rollback-only wrapping, which never commits and so never actually evaluates it, but
    // concurrentRevokeIfActiveCallsAgainstTheSameTokenNeverBothWin_realConcurrencyProof below opts
    // out of that wrapping (Propagation.NOT_SUPPORTED) to prove real cross-transaction concurrency,
    // and genuinely commits. Both inserts must land in the same transaction, or the account-only
    // insert already violates BR-ID-02 before the credential insert below ever runs.
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              jdbcTemplate.update(
                  "insert into accounts (id, organization_id, email, status, created_at) "
                      + "values (?, ?, ?, 'ACTIVE', now())",
                  accountId.value(),
                  UUID.randomUUID(),
                  "refresh-token-owner-" + accountId.value() + "@example.com");
              jdbcTemplate.update(
                  "insert into password_credentials (account_id, password_hash) values (?, ?)",
                  accountId.value(),
                  "not-a-real-hash-this-suite-never-logs-in");
            });
    session = Session.open(accountId, List.of("openid"));
    sessions.save(session);
  }

  @Test
  void savesAndFindsByTokenHash_reconstituteKeepsTheRealPersistedIdAndNoRotationParent() {
    RefreshToken token =
        RefreshToken.issue(session.id(), accountId, "a-hash", Instant.now().plusSeconds(3600));

    repository.save(token);
    Optional<RefreshToken> found = repository.findByTokenHash("a-hash");

    assertThat(found).isPresent();
    assertThat(found.get().id()).isEqualTo(token.id());
    assertThat(found.get().sessionId()).isEqualTo(session.id());
    assertThat(found.get().accountId()).isEqualTo(accountId);
    assertThat(found.get().rotatedFromId()).isEmpty();
    assertThat(found.get().revokedAt()).isEmpty();
  }

  @Test
  void findByTokenHashIsEmptyForAnUnknownHash() {
    assertThat(repository.findByTokenHash("never-issued")).isEmpty();
  }

  @Test
  void savingARotatedTokenPersistsItsRotationParent() {
    RefreshToken original =
        RefreshToken.issue(session.id(), accountId, "old-hash", Instant.now().plusSeconds(3600));
    repository.save(original);

    RefreshToken rotated =
        RefreshToken.rotatedFrom(original, "new-hash", Instant.now().plusSeconds(3600));
    repository.save(rotated);

    RefreshToken found = repository.findByTokenHash("new-hash").orElseThrow();
    assertThat(found.rotatedFromId()).contains(original.id());
  }

  @Test
  void savePersistsARevokedTokenCorrectly() {
    RefreshToken token =
        RefreshToken.issue(session.id(), accountId, "a-hash", Instant.now().plusSeconds(3600));
    token.revoke();

    repository.save(token);

    RefreshToken found = repository.findByTokenHash("a-hash").orElseThrow();
    assertThat(found.isRevoked()).isTrue();
  }

  @Test
  void revokeAllActiveForAccountRevokesEveryActiveTokenButLeavesOtherAccountsUntouched() {
    AccountId otherAccountId = new AccountId(UUID.randomUUID());
    jdbcTemplate.update(
        "insert into accounts (id, organization_id, email, status, created_at) "
            + "values (?, ?, ?, 'ACTIVE', now())",
        otherAccountId.value(),
        UUID.randomUUID(),
        "other-" + otherAccountId.value() + "@example.com");
    Session otherAccountSession = Session.open(otherAccountId, List.of("openid"));
    sessions.save(otherAccountSession);

    RefreshToken first =
        RefreshToken.issue(session.id(), accountId, "hash-one", Instant.now().plusSeconds(3600));
    RefreshToken second =
        RefreshToken.issue(session.id(), accountId, "hash-two", Instant.now().plusSeconds(3600));
    RefreshToken otherAccountToken =
        RefreshToken.issue(
            otherAccountSession.id(),
            otherAccountId,
            "hash-three",
            Instant.now().plusSeconds(3600));
    repository.save(first);
    repository.save(second);
    repository.save(otherAccountToken);

    repository.revokeAllActiveForAccount(accountId);

    assertThat(repository.findByTokenHash("hash-one").orElseThrow().isActive()).isFalse();
    assertThat(repository.findByTokenHash("hash-two").orElseThrow().isActive()).isFalse();
    assertThat(repository.findByTokenHash("hash-three").orElseThrow().isActive()).isTrue();
  }

  // RefreshTokenRepository#revokeIfActive's own Javadoc (SDE-III review, 2026-09-14) — the whole
  // fix is that a second call against an already-revoked row reports 0 rows touched, not 1. A
  // single-threaded, deterministic proof of that row-count semantic; real concurrent-caller
  // behavior is proved separately below, since sequential calls in one transaction can't show
  // whether a genuinely concurrent second caller is forced to re-check the WHERE clause.
  @Test
  void revokeIfActiveReturnsTrueOnceThenFalseForTheSameToken() {
    RefreshToken token =
        RefreshToken.issue(session.id(), accountId, "race-hash", Instant.now().plusSeconds(3600));
    repository.save(token);

    boolean firstCall = repository.revokeIfActive(token.id(), Instant.now());
    boolean secondCall = repository.revokeIfActive(token.id(), Instant.now());

    assertThat(firstCall).as("the only call against a still-active token must win").isTrue();
    assertThat(secondCall)
        .as("a second call against the now-already-revoked row must report it lost, not repeat")
        .isFalse();
    assertThat(repository.findByTokenHash("race-hash").orElseThrow().isRevoked()).isTrue();
  }

  // BR-ID-03's real TOCTOU-fix proof: RotateRefreshTokenServiceTest already proves the service
  // layer treats a lost race as reuse using a mocked repository, which can only show the *service*
  // reacts correctly to a stubbed false — it cannot prove PostgreSQL itself actually forces the
  // loser of two genuinely concurrent transactions to observe that false. This test proves that,
  // against a real database, the same way JpaWebhookDeliveryRepositoryTest's own SKIP LOCKED proof
  // does for its own concurrency guarantee — two separate threads, two separate transactions, two
  // separate connections, racing the exact same row.
  //
  // Propagation.NOT_SUPPORTED: escapes this class's own @Transactional (which would otherwise wrap
  // this method's @BeforeEach-seeded row in an uncommitted outer transaction invisible to the two
  // genuinely separate transactions below) — the seeded token here commits for real, exactly like
  // JpaWebhookDeliveryRepositoryTest's own un-annotated class already relies on.
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void concurrentRevokeIfActiveCallsAgainstTheSameTokenNeverBothWin_realConcurrencyProof() {
    RefreshToken token =
        RefreshToken.issue(
            session.id(), accountId, "concurrent-race-hash", Instant.now().plusSeconds(3600));
    repository.save(token);
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

    CompletableFuture<Boolean> callA =
        CompletableFuture.supplyAsync(
            () ->
                transactionTemplate.execute(
                    status -> repository.revokeIfActive(token.id(), Instant.now())));
    CompletableFuture<Boolean> callB =
        CompletableFuture.supplyAsync(
            () ->
                transactionTemplate.execute(
                    status -> repository.revokeIfActive(token.id(), Instant.now())));

    boolean wonByA = Boolean.TRUE.equals(callA.join());
    boolean wonByB = Boolean.TRUE.equals(callB.join());

    assertThat(wonByA ^ wonByB)
        .as("exactly one of two concurrent callers may ever revoke the same active token")
        .isTrue();
    assertThat(repository.findByTokenHash("concurrent-race-hash").orElseThrow().isRevoked())
        .isTrue();
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = SpringDataRefreshTokenJpaRepository.class,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = {
                SpringDataRefreshTokenJpaRepository.class,
                SpringDataSessionJpaRepository.class
              }))
  @Import({JpaRefreshTokenRepository.class, JpaSessionRepository.class})
  static class TestConfig {}
}
