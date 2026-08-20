package com.clavaris.identity.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
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
