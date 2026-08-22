package com.clavaris.identity.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccount;
import java.util.Optional;
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
 * test-strategy.md §2: a real-Postgres integration test for {@code JpaPlatformAccountRepository} —
 * mirrors {@code JpaAccountRepositoryTest}, proving the attached {@code PlatformPasswordCredential}
 * round-trips and that email lookup is global (no organization scoping to prove, unlike the tenant
 * repository's own test).
 */
@SpringBootTest(classes = JpaPlatformAccountRepositoryTest.TestConfig.class)
@Testcontainers
@Transactional
class JpaPlatformAccountRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private PlatformAccountRepository repository;

  @Test
  void findsAPlatformAccountWithItsAttachedPasswordCredential() {
    Email email = new Email("stored-founder@example.com");
    PlatformAccount account = PlatformAccount.register(email);
    account.attachPasswordCredential("argon2id$stored-hash");
    repository.save(account);

    Optional<PlatformAccount> found = repository.findByEmail(email);

    assertThat(found).isPresent();
    assertThat(found.get().id()).isEqualTo(account.id());
    assertThat(found.get().email()).isEqualTo(email);
    assertThat(found.get().passwordCredential()).isPresent();
    assertThat(found.get().passwordCredential().orElseThrow().passwordHash())
        .isEqualTo("argon2id$stored-hash");
  }

  @Test
  void findsByIdToo() {
    Email email = new Email("by-id-founder@example.com");
    PlatformAccount account = PlatformAccount.register(email);
    account.attachPasswordCredential("argon2id$stored-hash");
    repository.save(account);

    assertThat(repository.findById(account.id())).isPresent();
  }

  @Test
  void returnsEmptyForAnUnknownEmail() {
    assertThat(repository.findByEmail(new Email("nobody@example.com"))).isEmpty();
  }

  @Test
  void existsByEmailReflectsWhatWasActuallySaved() {
    Email email = new Email("exists-check@example.com");
    assertThat(repository.existsByEmail(email)).isFalse();

    PlatformAccount account = PlatformAccount.register(email);
    account.attachPasswordCredential("argon2id$stored-hash");
    repository.save(account);

    assertThat(repository.existsByEmail(email)).isTrue();
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = SpringDataPlatformAccountJpaRepository.class,
      includeFilters = {
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = SpringDataPlatformAccountJpaRepository.class),
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = SpringDataPlatformPasswordCredentialJpaRepository.class)
      })
  @Import(JpaPlatformAccountRepository.class)
  static class TestConfig {}
}
