package com.clavaris.identity.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real-Postgres proof of migration {@code V20260830110000}'s own deferred constraint triggers — see
 * {@link AccountAuthMethodIntegrityCheckJob}'s own Javadoc for the design reasoning.
 *
 * <p>Deliberately NOT {@code @Transactional} at the class/method level, unlike every other
 * persistence test in this package: Spring's own test-managed transaction rolls back at the end of
 * each test rather than committing, and a {@code DEFERRABLE INITIALLY DEFERRED} constraint trigger
 * only fires at a real {@code COMMIT} — a rollback would never actually exercise it. Each test
 * drives its own real, committing transaction via a plain {@link TransactionTemplate} instead, and
 * cleans up its own rows afterward since there is no rollback safety net here.
 */
@SpringBootTest(classes = AccountAuthMethodDeferredConstraintTriggerTest.TestConfig.class)
@Testcontainers
class AccountAuthMethodDeferredConstraintTriggerTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void rejectsAtCommitWhenANewAccountHasNoAuthMethodAtAll() {
    UUID accountId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

    try {
      assertThatThrownBy(
              () ->
                  transactionTemplate.executeWithoutResult(
                      status ->
                          jdbcTemplate.update(
                              "insert into accounts"
                                  + " (id, organization_id, email, status, created_at)"
                                  + " values (?, ?, ?, 'ACTIVE', now())",
                              accountId,
                              organizationId,
                              "trigger-test-orphan-" + accountId + "@example.com")))
          .as(
              "BR-ID-02: an account with neither a password credential nor a social identity"
                  + " must fail at commit, not silently persist")
          // Spring wraps the real PSQLException in its own TransactionSystemException ("JDBC
          // commit failed") — the trigger's own message lives in the cause chain, not the
          // top-level message, so this checks the full stack trace text (which includes every
          // "Caused by:" section) rather than getMessage() alone.
          .hasStackTraceContaining("BR-ID-02 violated");
    } finally {
      jdbcTemplate.update("delete from accounts where id = ?", accountId);
    }
  }

  @Test
  void commitsSuccessfullyWhenAPasswordCredentialExistsInTheSameTransaction() {
    UUID accountId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

    try {
      transactionTemplate.executeWithoutResult(
          status -> {
            jdbcTemplate.update(
                "insert into accounts (id, organization_id, email, status, created_at)"
                    + " values (?, ?, ?, 'ACTIVE', now())",
                accountId,
                organizationId,
                "trigger-test-with-password-" + accountId + "@example.com");
            jdbcTemplate.update(
                "insert into password_credentials (id, account_id, password_hash, updated_at)"
                    + " values (?, ?, ?, now())",
                UUID.randomUUID(),
                accountId,
                "argon2id$trigger-test-hash");
          });

      assertThat(
              jdbcTemplate.queryForObject(
                  "select count(*) from accounts where id = ?", Integer.class, accountId))
          .isEqualTo(1);
    } finally {
      jdbcTemplate.update("delete from accounts where id = ?", accountId);
    }
  }

  @Test
  void commitsSuccessfullyWhenASocialIdentityExistsInTheSameTransaction() {
    // The whole point of a DEFERRED trigger, not an IMMEDIATE one: the FK forces the account row
    // to be inserted before the social identity row that references it, but this test's own
    // point is that the trigger only cares about the state at commit time, never about that
    // necessary ordering.
    UUID accountId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

    try {
      transactionTemplate.executeWithoutResult(
          status -> {
            jdbcTemplate.update(
                "insert into accounts (id, organization_id, email, status, created_at)"
                    + " values (?, ?, ?, 'ACTIVE', now())",
                accountId,
                organizationId,
                "trigger-test-with-social-" + accountId + "@example.com");
            jdbcTemplate.update(
                "insert into social_identities"
                    + " (id, account_id, organization_id, provider, provider_user_id)"
                    + " values (?, ?, ?, 'GOOGLE', ?)",
                UUID.randomUUID(),
                accountId,
                organizationId,
                "trigger-test-google-sub-" + accountId);
          });

      assertThat(
              jdbcTemplate.queryForObject(
                  "select count(*) from accounts where id = ?", Integer.class, accountId))
          .isEqualTo(1);
    } finally {
      jdbcTemplate.update("delete from accounts where id = ?", accountId);
    }
  }

  @Test
  void rejectsAtCommitForAPlatformAccountWithNoAuthMethodEither() {
    UUID platformAccountId = UUID.randomUUID();
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

    try {
      assertThatThrownBy(
              () ->
                  transactionTemplate.executeWithoutResult(
                      status ->
                          jdbcTemplate.update(
                              "insert into platform_accounts (id, email, status, created_at)"
                                  + " values (?, ?, 'ACTIVE', now())",
                              platformAccountId,
                              "trigger-test-platform-orphan-"
                                  + platformAccountId
                                  + "@example.com")))
          .hasStackTraceContaining("BR-ID-02 violated");
    } finally {
      jdbcTemplate.update("delete from platform_accounts where id = ?", platformAccountId);
    }
  }

  // Deliberately no @EnableJpaRepositories/@Import here — this test only ever issues raw SQL via
  // JdbcTemplate, and a plain DataSourceTransactionManager (not JpaTransactionManager) avoids
  // bootstrapping Hibernate for a test that has no entities of its own to map.
  @Configuration
  @EnableAutoConfiguration
  static class TestConfig {

    @Bean
    /* package */ PlatformTransactionManager transactionManager(final DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }
  }
}
