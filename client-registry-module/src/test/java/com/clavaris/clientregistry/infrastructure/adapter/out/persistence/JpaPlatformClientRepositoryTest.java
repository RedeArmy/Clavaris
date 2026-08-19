package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.PlatformClientRepository;
import com.clavaris.clientregistry.domain.model.PlatformClient;
import com.clavaris.clientregistry.domain.model.PlatformScopes;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * test-strategy.md §2: persistence adapters get a real-Postgres integration test, not just a
 * mocked-port unit test — proves {@code toDomain()} really calls {@code
 * PlatformClient.reconstitute(...)} and not {@code register()} (the exact bug class already caught
 * once in {@code JpaAccountRepository}'s own history), and that the JSON-serialized {@code
 * allowed_scopes} column round-trips correctly against the real migrated schema.
 *
 * <p>Deliberately NOT {@code @DataJpaTest}: confirmed live that Spring Boot 4.1's
 * spring-boot-test-autoconfigure jar no longer contains {@code
 * org.springframework.boot.test.autoconfigure.orm.jpa} at all (same removal pattern already hit
 * this session with {@code @WebMvcTest}/{@code @AutoConfigureMockMvc}/{@code TestRestTemplate}).
 * This hand-assembles the same thing that test slice used to: a minimal {@code @SpringBootTest}
 * context scoped to this module's own persistence package, against a real Flyway-migrated schema
 * (not Hibernate-generated DDL) — so it also catches entity/migration drift, not just mapping bugs.
 */
@SpringBootTest(classes = JpaPlatformClientRepositoryTest.TestConfig.class)
@Testcontainers
class JpaPlatformClientRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private PlatformClientRepository repository;

  @Test
  void savesAndReadsBackAPlatformClient_reconstituteKeepsTheRealPersistedId() {
    final PlatformClient client =
        PlatformClient.register(
            "bootstrap-client", "$argon2id$hashed", PlatformScopes.BOOTSTRAP_DEFAULT);

    repository.save(client);
    final Optional<PlatformClient> found = repository.findByClientId("bootstrap-client");

    assertThat(found).isPresent();
    assertThat(found.get().id()).isEqualTo(client.id());
    assertThat(found.get().clientSecretHash()).isEqualTo("$argon2id$hashed");
    assertThat(found.get().allowedScopes()).containsExactly(PlatformScopes.ORGANIZATIONS_WRITE);
  }

  @Test
  void existsByClientIdReflectsWhatWasActuallyPersisted() {
    assertThat(repository.existsByClientId("not-yet-registered")).isFalse();

    repository.save(
        PlatformClient.register(
            "now-registered", "$argon2id$hashed", PlatformScopes.BOOTSTRAP_DEFAULT));

    assertThat(repository.existsByClientId("now-registered")).isTrue();
  }

  @Test
  void findByClientIdIsEmptyForAnUnknownClient() {
    assertThat(repository.findByClientId(UUID.randomUUID().toString())).isEmpty();
  }

  // Package-scoped so it shares this test's package with PlatformClientEntity and
  // JpaPlatformClientRepository — Boot's default entity/repository scan base package is the
  // package of the class carrying @EnableAutoConfiguration, so no explicit @EntityScan is needed.
  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(basePackageClasses = SpringDataPlatformClientJpaRepository.class)
  @ComponentScan(basePackageClasses = JpaPlatformClientRepository.class)
  static class TestConfig {}
}
