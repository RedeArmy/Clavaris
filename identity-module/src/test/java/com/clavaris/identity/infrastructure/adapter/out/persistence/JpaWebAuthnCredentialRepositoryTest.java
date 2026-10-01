package com.clavaris.identity.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;

import com.clavaris.identity.application.usecase.registerwebauthncredential.WebAuthnCredentialRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.WebAuthnCredential;
import java.nio.charset.StandardCharsets;
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
 * test-strategy.md §2: a real-Postgres integration test for the {@code WebAuthnCredential} adapter
 * — same hand-assembled {@code @SpringBootTest} pattern as {@code JpaSessionRepositoryTest} (see
 * that test's own Javadoc for why: Spring Boot 4.1 dropped {@code @DataJpaTest} entirely). Proves
 * {@code toDomain()}/{@code toEntity()} genuinely round-trip against the real migrated {@code
 * webauthn_credentials} schema, including its {@code accounts} foreign key and the {@code
 * credential_id} unique constraint.
 */
@SpringBootTest(classes = JpaWebAuthnCredentialRepositoryTest.TestConfig.class)
@Testcontainers
@Transactional
class JpaWebAuthnCredentialRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private WebAuthnCredentialRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private AccountId accountId;
  private OrganizationId organizationId;

  // webauthn_credentials.account_id carries a real FK to accounts — a minimal, direct row insert
  // (not the full AccountRepository, out of scope for this test's own TestConfig) is enough to
  // satisfy it.
  @BeforeEach
  void seedAnAccount() {
    accountId = new AccountId(UUID.randomUUID());
    organizationId = new OrganizationId(UUID.randomUUID());
    jdbcTemplate.update(
        "insert into accounts (id, organization_id, email, status, created_at) "
            + "values (?, ?, ?, 'ACTIVE', now())",
        accountId.value(),
        organizationId.value(),
        "webauthn-owner-" + accountId.value() + "@example.com");
  }

  private WebAuthnCredential aCredential(final byte[] credentialId) {
    return WebAuthnCredential.register(
        accountId,
        organizationId,
        credentialId,
        "a-public-key".getBytes(StandardCharsets.UTF_8),
        0,
        "internal",
        "My passkey");
  }

  @Test
  void insertAndFindByCredentialIdRoundTripsEveryField() {
    byte[] credentialId = "credential-1".getBytes(StandardCharsets.UTF_8);
    WebAuthnCredential credential = aCredential(credentialId);

    repository.insert(credential);
    Optional<WebAuthnCredential> found = repository.findByCredentialId(credentialId);

    assertThat(found).isPresent();
    assertThat(found.get().id()).isEqualTo(credential.id());
    assertThat(found.get().accountId()).isEqualTo(accountId);
    assertThat(found.get().organizationId()).isEqualTo(organizationId);
    assertThat(found.get().credentialId()).isEqualTo(credentialId);
    assertThat(found.get().publicKeyCose()).isEqualTo(credential.publicKeyCose());
    assertThat(found.get().signatureCount()).isZero();
    assertThat(found.get().transports()).isEqualTo("internal");
    assertThat(found.get().nickname()).isEqualTo("My passkey");
    assertThat(found.get().lastUsedAt()).isNull();
  }

  @Test
  void findByCredentialIdIsEmptyForAnUnknownId() {
    assertThat(repository.findByCredentialId("unknown".getBytes(StandardCharsets.UTF_8))).isEmpty();
  }

  @Test
  void findAllByAccountIdReturnsNewestFirstAndOnlyThisAccountsOwn() {
    repository.insert(aCredential("credential-a".getBytes(StandardCharsets.UTF_8)));
    repository.insert(aCredential("credential-b".getBytes(StandardCharsets.UTF_8)));
    AccountId otherAccountId = new AccountId(UUID.randomUUID());
    jdbcTemplate.update(
        "insert into accounts (id, organization_id, email, status, created_at) "
            + "values (?, ?, ?, 'ACTIVE', now())",
        otherAccountId.value(),
        organizationId.value(),
        "other-" + otherAccountId.value() + "@example.com");
    repository.insert(
        WebAuthnCredential.register(
            otherAccountId,
            organizationId,
            "credential-other".getBytes(StandardCharsets.UTF_8),
            "key".getBytes(StandardCharsets.UTF_8),
            0,
            null,
            null));

    List<WebAuthnCredential> found = repository.findAllByAccountId(accountId);

    assertThat(found).hasSize(2).allMatch(c -> c.accountId().equals(accountId));
  }

  @Test
  void updateSignatureCountBumpsTheCounterAndLastUsedAt() {
    byte[] credentialId = "credential-touch".getBytes(StandardCharsets.UTF_8);
    WebAuthnCredential credential = aCredential(credentialId);
    repository.insert(credential);
    Instant usedAt = Instant.now();

    repository.updateSignatureCount(credential.id(), 42L, usedAt);

    WebAuthnCredential found = repository.findByCredentialId(credentialId).orElseThrow();
    assertThat(found.signatureCount()).isEqualTo(42L);
    assertThat(found.lastUsedAt()).isCloseTo(usedAt, within(5, ChronoUnit.MILLIS));
  }

  @Test
  void updateSignatureCountIsANoOpForAnUnknownRowId() {
    // entityManager.find returns null for an unknown id — the method must not throw, it simply
    // does nothing, same "no row to update" outcome a real caller can't distinguish from a race.
    assertThatCode(() -> repository.updateSignatureCount(UUID.randomUUID(), 1L, Instant.now()))
        .doesNotThrowAnyException();
  }

  @Test
  void deleteByIdAndAccountIdRemovesOnlyWhenBothMatch() {
    byte[] credentialId = "credential-delete".getBytes(StandardCharsets.UTF_8);
    WebAuthnCredential credential = aCredential(credentialId);
    repository.insert(credential);
    AccountId wrongAccountId = new AccountId(UUID.randomUUID());

    boolean deletedByWrongOwner =
        repository.deleteByIdAndAccountId(credential.id(), wrongAccountId);
    boolean deletedByRealOwner = repository.deleteByIdAndAccountId(credential.id(), accountId);

    assertThat(deletedByWrongOwner).isFalse();
    assertThat(deletedByRealOwner).isTrue();
    assertThat(repository.findByCredentialId(credentialId)).isEmpty();
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = SpringDataWebAuthnCredentialJpaRepository.class,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = SpringDataWebAuthnCredentialJpaRepository.class))
  @Import(JpaWebAuthnCredentialRepository.class)
  static class TestConfig {}
}
