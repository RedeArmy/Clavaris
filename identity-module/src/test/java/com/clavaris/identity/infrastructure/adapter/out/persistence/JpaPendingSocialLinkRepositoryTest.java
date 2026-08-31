package com.clavaris.identity.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.PendingSocialLinkRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.PendingSocialLink;
import com.clavaris.identity.domain.model.SocialProvider;
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
 * test-strategy.md §2: a real-Postgres integration test for the {@code PendingSocialLink} adapter —
 * same rationale as {@code JpaVerificationTokenRepositoryTest}.
 */
@SpringBootTest(classes = JpaPendingSocialLinkRepositoryTest.TestConfig.class)
@Testcontainers
@Transactional
class JpaPendingSocialLinkRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private PendingSocialLinkRepository repository;
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
        "pending-link-owner-" + accountId.value() + "@example.com");
  }

  @Test
  void savesAndFindsByConfirmationTokenHash_reconstituteKeepsTheRealPersistedIdAndProvider() {
    PendingSocialLink pendingLink =
        PendingSocialLink.raise(
            accountId,
            SocialProvider.GOOGLE,
            "google-sub-1",
            "a-hash",
            Instant.now().plusSeconds(3600));

    repository.save(pendingLink);
    Optional<PendingSocialLink> found = repository.findByConfirmationTokenHash("a-hash");

    assertThat(found).isPresent();
    assertThat(found.get().id()).isEqualTo(pendingLink.id());
    assertThat(found.get().accountId()).isEqualTo(accountId);
    assertThat(found.get().provider()).isEqualTo(SocialProvider.GOOGLE);
    assertThat(found.get().providerUserId()).isEqualTo("google-sub-1");
    assertThat(found.get().consumedAt()).isEmpty();
  }

  @Test
  void findByConfirmationTokenHashIsEmptyForAnUnknownHash() {
    assertThat(repository.findByConfirmationTokenHash("never-issued")).isEmpty();
  }

  @Test
  void savePersistsAConsumedPendingLinkCorrectly() {
    PendingSocialLink pendingLink =
        PendingSocialLink.raise(
            accountId, SocialProvider.GITHUB, "gh-1", "a-hash", Instant.now().plusSeconds(3600));
    pendingLink.consume();

    repository.save(pendingLink);

    PendingSocialLink found = repository.findByConfirmationTokenHash("a-hash").orElseThrow();
    assertThat(found.provider()).isEqualTo(SocialProvider.GITHUB);
    assertThat(found.consumedAt()).isPresent();
    assertThat(found.isActive()).isFalse();
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = SpringDataPendingSocialLinkJpaRepository.class,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = SpringDataPendingSocialLinkJpaRepository.class))
  @Import(JpaPendingSocialLinkRepository.class)
  static class TestConfig {}
}
