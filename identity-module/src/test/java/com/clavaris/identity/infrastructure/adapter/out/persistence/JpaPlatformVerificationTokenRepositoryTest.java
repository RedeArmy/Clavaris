package com.clavaris.identity.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.PlatformVerificationTokenRepository;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.PlatformVerificationToken;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * test-strategy.md §2: a real-Postgres integration test — mirrors {@code
 * JpaVerificationTokenRepositoryTest}.
 */
@SpringBootTest(classes = JpaPlatformVerificationTokenRepositoryTest.TestConfig.class)
@Testcontainers
@Transactional
class JpaPlatformVerificationTokenRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private PlatformVerificationTokenRepository repository;
  @Autowired private PlatformAccountRepository accounts;
  @Autowired private PlatformTransactionManager transactionManager;

  private PlatformAccount account;

  @BeforeEach
  void seedAPlatformAccount() {
    // Real-commit requirement JpaVerificationTokenRepositoryTest's own seed method documents —
    // concurrentConsumeIfActiveCallsAgainstTheSameTokenNeverBothWin_realConcurrencyProof below opts
    // out of the class-level @Transactional (Propagation.NOT_SUPPORTED) to prove genuine
    // cross-transaction concurrency, so the seeded row must actually be committed, not left inside
    // this class's rollback-only wrapping. Since every test's row now genuinely commits (not just
    // that one test's), the email must be unique per test run too — a fixed literal email would
    // collide with the previous test's already-committed row on ux_platform_accounts_email.
    account =
        PlatformAccount.register(
            new Email("verification-owner-" + UUID.randomUUID() + "@example.com"));
    account.attachPasswordCredential("argon2id$hash");
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> accounts.save(account));
  }

  @Test
  void savesAndFindsByTokenHash_reconstituteKeepsTheRealPersistedIdAndType() {
    PlatformVerificationToken token =
        PlatformVerificationToken.issue(
            account.id(),
            VerificationTokenType.EMAIL_VERIFICATION,
            "a-hash",
            Instant.now().plusSeconds(3600));

    repository.save(token);
    Optional<PlatformVerificationToken> found = repository.findByTokenHash("a-hash");

    assertThat(found).isPresent();
    assertThat(found.get().id()).isEqualTo(token.id());
    assertThat(found.get().platformAccountId()).isEqualTo(account.id());
    assertThat(found.get().type()).isEqualTo(VerificationTokenType.EMAIL_VERIFICATION);
    assertThat(found.get().consumedAt()).isEmpty();
  }

  @Test
  void findByTokenHashIsEmptyForAnUnknownHash() {
    assertThat(repository.findByTokenHash("never-issued")).isEmpty();
  }

  @Test
  void savePersistsAConsumedPasswordResetTokenCorrectly() {
    PlatformVerificationToken token =
        PlatformVerificationToken.issue(
            account.id(),
            VerificationTokenType.PASSWORD_RESET,
            "a-hash",
            Instant.now().plusSeconds(3600));
    token.consume();

    repository.save(token);

    PlatformVerificationToken found = repository.findByTokenHash("a-hash").orElseThrow();
    assertThat(found.type()).isEqualTo(VerificationTokenType.PASSWORD_RESET);
    assertThat(found.consumedAt()).isPresent();
    assertThat(found.isActive()).isFalse();
  }

  // Mirrors
  // JpaVerificationTokenRepositoryTest#consumeIfActiveReturnsTrueOnceThenFalseForTheSameToken
  // exactly — see that test's own Javadoc for the full rationale.
  @Test
  void consumeIfActiveReturnsTrueOnceThenFalseForTheSameToken() {
    PlatformVerificationToken token =
        PlatformVerificationToken.issue(
            account.id(),
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

  // Mirrors
  // JpaVerificationTokenRepositoryTest#concurrentConsumeIfActiveCallsAgainstTheSameTokenNeverBothWin_realConcurrencyProof
  // exactly — see that test's own Javadoc for the full rationale.
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void concurrentConsumeIfActiveCallsAgainstTheSameTokenNeverBothWin_realConcurrencyProof() {
    PlatformVerificationToken token =
        PlatformVerificationToken.issue(
            account.id(),
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
      basePackageClasses = SpringDataPlatformVerificationTokenJpaRepository.class,
      includeFilters = {
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = SpringDataPlatformVerificationTokenJpaRepository.class),
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = SpringDataPlatformAccountJpaRepository.class),
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = SpringDataPlatformPasswordCredentialJpaRepository.class)
      })
  @Import({JpaPlatformVerificationTokenRepository.class, JpaPlatformAccountRepository.class})
  static class TestConfig {}
}
