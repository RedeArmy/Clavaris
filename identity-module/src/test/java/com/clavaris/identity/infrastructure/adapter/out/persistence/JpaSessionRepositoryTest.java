package com.clavaris.identity.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Session;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * test-strategy.md §2: a real-Postgres integration test for the {@code Session} adapter — proves
 * {@code toDomain()} really calls {@code Session.reconstitute(...)} and that {@code scopes} (a JSON
 * text column, same convention as {@code OAuthClientEntity}'s list columns) round-trips correctly
 * against the real migrated schema, including its {@code accounts} foreign key.
 *
 * <p>Same hand-assembled {@code @SpringBootTest} pattern as {@code JpaSigningKeyRepositoryTest} —
 * see that test's own Javadoc for why (Spring Boot 4.1 dropped {@code @DataJpaTest} entirely).
 */
@SpringBootTest(classes = JpaSessionRepositoryTest.TestConfig.class)
@Testcontainers
@Transactional
class JpaSessionRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private SessionRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private AccountId accountId;

  // sessions.account_id carries a real FK to accounts — a minimal, direct row insert (not the
  // full AccountRepository, out of scope for this test's own TestConfig) is enough to satisfy it.
  @BeforeEach
  void seedAnAccount() {
    accountId = new AccountId(UUID.randomUUID());
    jdbcTemplate.update(
        "insert into accounts (id, organization_id, email, status, created_at) "
            + "values (?, ?, ?, 'ACTIVE', now())",
        accountId.value(),
        UUID.randomUUID(),
        "session-owner-" + accountId.value() + "@example.com");
  }

  @Test
  void savesAndFindsASession_reconstituteKeepsTheRealPersistedId() {
    Session session = Session.open(accountId, List.of("openid", "profile"));

    repository.save(session);
    Optional<Session> found = repository.findById(session.id());

    assertThat(found).isPresent();
    assertThat(found.get().accountId()).isEqualTo(accountId);
    assertThat(found.get().scopes()).containsExactly("openid", "profile");
    assertThat(found.get().revokedAt()).isEmpty();
  }

  @Test
  void findByIdIsEmptyForAnUnknownId() {
    assertThat(repository.findById(UUID.randomUUID())).isEmpty();
  }

  @Test
  void savePersistsARevokedSessionCorrectly() {
    Session session = Session.open(accountId, List.of("openid"));
    session.revoke();

    repository.save(session);
    Optional<Session> found = repository.findById(session.id());

    assertThat(found).isPresent();
    assertThat(found.get().revokedAt()).isPresent();
    assertThat(found.get().isActive()).isFalse();
  }

  @Test
  void revokeAllActiveForAccountRevokesEveryActiveSessionButLeavesOtherAccountsUntouched() {
    AccountId otherAccountId = new AccountId(UUID.randomUUID());
    jdbcTemplate.update(
        "insert into accounts (id, organization_id, email, status, created_at) "
            + "values (?, ?, ?, 'ACTIVE', now())",
        otherAccountId.value(),
        UUID.randomUUID(),
        "other-" + otherAccountId.value() + "@example.com");
    Session first = Session.open(accountId, List.of("openid"));
    Session second = Session.open(accountId, List.of("openid"));
    Session otherAccountSession = Session.open(otherAccountId, List.of("openid"));
    repository.save(first);
    repository.save(second);
    repository.save(otherAccountSession);

    repository.revokeAllActiveForAccount(accountId);

    assertThat(repository.findById(first.id()).orElseThrow().isActive()).isFalse();
    assertThat(repository.findById(second.id()).orElseThrow().isActive()).isFalse();
    assertThat(repository.findById(otherAccountSession.id()).orElseThrow().isActive()).isTrue();
  }

  @Test
  void revokeAllActiveForAccountDoesNotReRevokeAnAlreadyRevokedSession() {
    // Idempotency check: an already-revoked session's own revokedAt timestamp must not be
    // silently overwritten by a later cascade call.
    Session session = Session.open(accountId, List.of("openid"));
    session.revoke();
    repository.save(session);
    Instant originalRevokedAt = session.revokedAt().orElseThrow();

    repository.revokeAllActiveForAccount(accountId);

    Instant afterCascade =
        repository.findById(session.id()).orElseThrow().revokedAt().orElseThrow();
    // Within a millisecond, not exactly equal: timestamptz only stores microsecond precision, so
    // a real Postgres round-trip truncates Instant.now()'s nanoseconds — a genuine DB behavior,
    // not a bug this assertion should be strict enough to flag.
    assertThat(afterCascade).isCloseTo(originalRevokedAt, within(5, ChronoUnit.MILLIS));
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = SpringDataSessionJpaRepository.class,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = SpringDataSessionJpaRepository.class))
  @Import(JpaSessionRepository.class)
  static class TestConfig {}
}
