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

  private AccountId accountId;
  private Session session;

  @BeforeEach
  void seedAnAccountAndSession() {
    accountId = new AccountId(UUID.randomUUID());
    jdbcTemplate.update(
        "insert into accounts (id, organization_id, email, status, created_at) "
            + "values (?, ?, ?, 'ACTIVE', now())",
        accountId.value(),
        UUID.randomUUID(),
        "refresh-token-owner-" + accountId.value() + "@example.com");
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
