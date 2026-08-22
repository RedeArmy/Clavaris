package com.clavaris.identity.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import java.time.Instant;
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

  private AccountId accountId;

  @BeforeEach
  void seedAnAccount() {
    accountId = new AccountId(UUID.randomUUID());
    jdbcTemplate.update(
        "insert into accounts (id, organization_id, email, status, created_at) "
            + "values (?, ?, ?, 'ACTIVE', now())",
        accountId.value(),
        UUID.randomUUID(),
        "verification-owner-" + accountId.value() + "@example.com");
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
