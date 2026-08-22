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
import org.springframework.transaction.annotation.Transactional;
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

  private PlatformAccount account;

  @BeforeEach
  void seedAPlatformAccount() {
    account = PlatformAccount.register(new Email("verification-owner@example.com"));
    account.attachPasswordCredential("argon2id$hash");
    accounts.save(account);
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
