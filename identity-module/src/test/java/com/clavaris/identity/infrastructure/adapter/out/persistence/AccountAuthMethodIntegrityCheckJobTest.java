package com.clavaris.identity.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * Real-Postgres proof of {@link AccountAuthMethodIntegrityCheckJob}'s own query — see that class's
 * own Javadoc for why this is a compensating control (a daily sweep), not a synchronous guard.
 */
@SpringBootTest(classes = AccountAuthMethodIntegrityCheckJobTest.TestConfig.class)
@Testcontainers
@Transactional
class AccountAuthMethodIntegrityCheckJobTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private AccountAuthMethodIntegrityCheckJob job;
  @Autowired private SpringDataAccountJpaRepository accounts;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void countsAnAccountWithNeitherAPasswordCredentialNorASocialIdentity() {
    insertAccount();

    assertThat(accounts.countAccountsWithNoAuthMethod()).isEqualTo(1L);
    // Doesn't throw — this job's own contract is "log a warning," never a hard failure.
    job.checkForOrphanedAccounts();
  }

  @Test
  void excludesAnAccountWithAPasswordCredential() {
    UUID accountId = insertAccount();
    jdbcTemplate.update(
        "insert into password_credentials (id, account_id, password_hash, updated_at) "
            + "values (?, ?, ?, now())",
        UUID.randomUUID(),
        accountId,
        "argon2id$hash");

    assertThat(accounts.countAccountsWithNoAuthMethod()).isZero();
  }

  @Test
  void excludesAnAccountWithALinkedSocialIdentity() {
    UUID accountId = insertAccount();
    jdbcTemplate.update(
        "insert into social_identities "
            + "(id, account_id, organization_id, provider, provider_user_id, linked_at) "
            + "values (?, ?, ?, 'GOOGLE', 'sub-123', now())",
        UUID.randomUUID(),
        accountId,
        UUID.randomUUID());

    assertThat(accounts.countAccountsWithNoAuthMethod()).isZero();
  }

  private UUID insertAccount() {
    UUID accountId = UUID.randomUUID();
    jdbcTemplate.update(
        "insert into accounts (id, organization_id, email, status, created_at) "
            + "values (?, ?, ?, 'ACTIVE', now())",
        accountId,
        UUID.randomUUID(),
        "orphan-" + accountId + "@example.com");
    return accountId;
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = SpringDataAccountJpaRepository.class,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = SpringDataAccountJpaRepository.class))
  @Import(AccountAuthMethodIntegrityCheckJob.class)
  static class TestConfig {}
}
