package com.clavaris.identity.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
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
 * test-strategy.md §2: a real-Postgres integration test for {@code findByOrganizationIdAndEmail} —
 * proves {@code toDomain()} really reconstitutes the attached {@code PasswordCredential} via {@code
 * PasswordCredential.reconstitute} (not silently dropping it), that the lookup is scoped by {@code
 * organizationId} (BR-ORG-02: never a global email lookup), and that an account with no credential
 * row reconstitutes with an empty one rather than throwing.
 *
 * <p>Same hand-assembled {@code @SpringBootTest} + {@code @Import}-scoped {@code TestConfig}
 * pattern as {@code JpaSigningKeyRepositoryTest} — see its own Javadoc for why (Spring Boot 4.1 has
 * no {@code @DataJpaTest} slice, and this package holds several sibling tests' own nested {@code
 * TestConfig} classes that a broad {@code @ComponentScan} would collide with).
 */
@SpringBootTest(classes = JpaAccountRepositoryTest.TestConfig.class)
@Testcontainers
@Transactional
class JpaAccountRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private AccountRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void findsAnAccountWithItsAttachedPasswordCredential() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    Email email = new Email("stored-user@example.com");
    Account account = Account.register(organizationId, email);
    account.attachPasswordCredential("argon2id$stored-hash");
    repository.save(account);

    Optional<Account> found = repository.findByOrganizationIdAndEmail(organizationId, email);

    assertThat(found).isPresent();
    assertThat(found.get().id()).isEqualTo(account.id());
    assertThat(found.get().email()).isEqualTo(email);
    assertThat(found.get().passwordCredential()).isPresent();
    assertThat(found.get().passwordCredential().orElseThrow().passwordHash())
        .isEqualTo("argon2id$stored-hash");
  }

  // ADR-0020 (Phase 6, live-verified): AuthenticateWithSocialProviderService#linkBrandNewAccount
  // saves an Account with no PasswordCredential at all for a brand-new social signup — BR-ID-02's
  // real invariant (never zero auth methods) is upheld by that same transaction also saving a
  // SocialIdentity, not by this repository requiring a password specifically. Confirmed here
  // against real Postgres, not just inspection — the prior version of save() threw on exactly this
  // case, a real 500 this phase's own end-to-end test caught live.
  @Test
  void savesAndReconstitutesASocialOnlyAccountWithNoPasswordCredential() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    Email email = new Email("social-only@example.com");
    Account account = Account.register(organizationId, email);

    repository.save(account);
    Optional<Account> found = repository.findByOrganizationIdAndEmail(organizationId, email);

    assertThat(found).isPresent();
    assertThat(found.get().passwordCredential()).isEmpty();
  }

  @Test
  void lookupIsScopedToOneOrganizationOnly() {
    // BR-ORG-02: the same email in a different Organization must never be resolvable here.
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    OrganizationId otherOrganizationId = new OrganizationId(UUID.randomUUID());
    Email email = new Email("shared-address@example.com");
    Account account = Account.register(organizationId, email);
    account.attachPasswordCredential("argon2id$stored-hash");
    repository.save(account);

    assertThat(repository.findByOrganizationIdAndEmail(organizationId, email)).isPresent();
    assertThat(repository.findByOrganizationIdAndEmail(otherOrganizationId, email)).isEmpty();
  }

  @Test
  void returnsEmptyForAnUnknownEmail() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

    assertThat(
            repository.findByOrganizationIdAndEmail(
                organizationId, new Email("nobody@example.com")))
        .isEmpty();
  }

  // BR-DATA-02/03, TD-migration V20260826100000: proves the schema-level guarantee
  // DeleteAccountService's own Javadoc relies on — every table whose only reason to exist is this
  // Account's own data is really gone, not just the account row itself, and the delete doesn't
  // throw a foreign-key violation partway through. Rows for sessions/refresh_tokens/
  // verification_tokens are inserted directly via SQL rather than through their own repositories
  // (not wired into this test's own narrow TestConfig) — this test is deliberately about the
  // database's own cascade behavior, independent of any one repository's application code.
  @Test
  void deletingAnAccountCascadesToEveryTableThatOnlyExistsBecauseOfIt() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    Email email = new Email("to-be-deleted@example.com");
    Account account = Account.register(organizationId, email);
    account.attachPasswordCredential("argon2id$stored-hash");
    repository.save(account);
    AccountId accountId = account.id();

    UUID sessionId = UUID.randomUUID();
    jdbcTemplate.update(
        "insert into sessions (id, account_id, scopes) values (?, ?, ?)",
        sessionId,
        accountId.value(),
        "[\"openid\"]");
    jdbcTemplate.update(
        "insert into refresh_tokens (id, session_id, account_id, token_hash, expires_at) values"
            + " (?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        sessionId,
        accountId.value(),
        "a".repeat(64),
        Timestamp.from(Instant.now().plusSeconds(3600)));
    jdbcTemplate.update(
        "insert into verification_tokens (id, account_id, type, token_hash, expires_at) values"
            + " (?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        accountId.value(),
        "EMAIL_VERIFICATION",
        "b".repeat(64),
        Timestamp.from(Instant.now().plusSeconds(3600)));

    repository.deleteById(accountId);

    assertThat(countWhere("accounts", accountId.value())).isZero();
    assertThat(countWhere("password_credentials", accountId.value())).isZero();
    assertThat(countWhere("sessions", accountId.value())).isZero();
    assertThat(countWhere("refresh_tokens", accountId.value())).isZero();
    assertThat(countWhere("verification_tokens", accountId.value())).isZero();
  }

  private Integer countWhere(String table, UUID accountId) {
    String column = "accounts".equals(table) ? "id" : "account_id";
    return jdbcTemplate.queryForObject(
        "select count(*) from " + table + " where " + column + " = ?", Integer.class, accountId);
  }

  // Same @Import + narrowly-filtered @EnableJpaRepositories rationale as
  // JpaSigningKeyRepositoryTest
  // — this package holds several sibling tests' own nested TestConfig classes.
  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = SpringDataAccountJpaRepository.class,
      includeFilters = {
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = SpringDataAccountJpaRepository.class),
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = SpringDataPasswordCredentialJpaRepository.class)
      })
  @Import(JpaAccountRepository.class)
  static class TestConfig {}
}
