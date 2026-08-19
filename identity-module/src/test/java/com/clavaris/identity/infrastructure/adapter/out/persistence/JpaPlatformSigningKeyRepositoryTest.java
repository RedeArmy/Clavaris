package com.clavaris.identity.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.application.usecase.activateplatformsigningkey.PlatformSigningKeyRepository;
import com.clavaris.identity.domain.model.PlatformSigningKey;
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
 * test-strategy.md §2: a real-Postgres integration test for the platform issuer's own signing-key
 * adapter. See {@link JpaSigningKeyRepositoryTest}'s Javadoc for why this isn't
 * {@code @DataJpaTest} (removed in Spring Boot 4.1, confirmed live).
 *
 * <p>{@code @Transactional}: unlike per-Organization {@code signing_keys}, {@code
 * platform_signing_keys} has no per-tenant scoping — there is only one platform tier, so {@code
 * findActive()} would otherwise see rows left behind by whichever test method ran first (confirmed
 * live: without this, {@code findActiveIgnoresARetiredKey} failed depending on run order). Each
 * test method now rolls back automatically.
 */
@SpringBootTest(classes = JpaPlatformSigningKeyRepositoryTest.TestConfig.class)
@Testcontainers
@Transactional
class JpaPlatformSigningKeyRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private PlatformSigningKeyRepository repository;

  @Test
  void savesAndFindsTheActiveKey_reconstituteKeepsTheRealPersistedId() {
    PlatformSigningKey key = PlatformSigningKey.activate("active-kid", "RS256");

    repository.save(key);
    Optional<PlatformSigningKey> found = repository.findActive();

    assertThat(found).isPresent();
    assertThat(found.get().id()).isEqualTo(key.id());
    assertThat(found.get().kid()).isEqualTo("active-kid");
    assertThat(found.get().algorithm()).isEqualTo("RS256");
  }

  @Test
  void findActiveIgnoresARetiredKey() {
    PlatformSigningKey key = PlatformSigningKey.activate("retired-kid", "RS256");
    key.retire();

    repository.save(key);

    assertThat(repository.findActive()).isEmpty();
  }

  @Test
  void findActivePrefersTheUnretiredKeyOverAnOlderRetiredOne() {
    PlatformSigningKey retired = PlatformSigningKey.activate("old-kid", "RS256");
    retired.retire();
    repository.save(retired);
    repository.save(PlatformSigningKey.activate("new-kid", "RS256"));

    Optional<PlatformSigningKey> found = repository.findActive();

    assertThat(found).isPresent();
    assertThat(found.get().kid()).isEqualTo("new-kid");
  }

  // @Import, not @ComponentScan — see JpaSigningKeyRepositoryTest's own TestConfig comment for
  // why: this package also holds that test's nested TestConfig, and scanning the whole package
  // here would pick it up too, double-registering Spring Data repositories across both contexts
  // (confirmed live).
  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = SpringDataPlatformSigningKeyJpaRepository.class,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = SpringDataPlatformSigningKeyJpaRepository.class))
  @Import(JpaPlatformSigningKeyRepository.class)
  static class TestConfig {}
}
