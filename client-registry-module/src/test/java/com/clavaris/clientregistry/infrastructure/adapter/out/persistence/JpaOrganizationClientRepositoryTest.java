package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientRepository;
import com.clavaris.clientregistry.domain.model.OrganizationClient;
import com.clavaris.clientregistry.domain.model.PlatformScopes;
import java.util.List;
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
 * ADR-0023: real-Postgres integration test, same pattern as {@code JpaPlatformClientRepositoryTest}
 * — proves {@code toDomain()} really calls {@code OrganizationClient.reconstitute(...)}, and that
 * the JSON-serialized {@code allowed_scopes} column round-trips correctly against the real migrated
 * schema. Class-level {@code @Transactional} (same precedent as {@code
 * JpaOrganizationSocialCredentialRepositoryTest}, organization-module): {@code
 * deleteAllByOrganizationId} is a derived delete query, which Spring Data JPA always executes via a
 * load-then-{@code EntityManager.remove()} strategy — needs a real transaction the framework's own
 * inherited {@code save()}/{@code findById()} get for free but a custom derived method does not.
 */
@SpringBootTest(classes = JpaOrganizationClientRepositoryTest.TestConfig.class)
@Testcontainers
@Transactional
class JpaOrganizationClientRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private OrganizationClientRepository repository;

  @Test
  void savesAndReadsBackAnOrganizationClient_reconstituteKeepsTheRealPersistedId() {
    UUID organizationId = UUID.randomUUID();
    OrganizationClient client =
        OrganizationClient.register(
            organizationId,
            "sk_test_abc",
            "$argon2id$hashed",
            List.of(PlatformScopes.ACCOUNTS_IMPERSONATE, PlatformScopes.WORKSPACES_WRITE));

    repository.save(client);
    Optional<OrganizationClient> found = repository.findByClientId("sk_test_abc");

    assertThat(found).isPresent();
    assertThat(found.get().id()).isEqualTo(client.id());
    assertThat(found.get().organizationId()).isEqualTo(organizationId);
    assertThat(found.get().clientSecretHash()).isEqualTo("$argon2id$hashed");
    assertThat(found.get().allowedScopes())
        .containsExactly(PlatformScopes.ACCOUNTS_IMPERSONATE, PlatformScopes.WORKSPACES_WRITE);
    assertThat(found.get().active()).isTrue();
  }

  @Test
  void findByClientIdIsEmptyForAnUnknownClient() {
    assertThat(repository.findByClientId(UUID.randomUUID().toString())).isEmpty();
  }

  @Test
  void findByIdReturnsTheSameClientLookedUpByItsOwnPersistedId() {
    OrganizationClient client =
        OrganizationClient.register(UUID.randomUUID(), "sk_test_id-lookup", "hash", List.of());
    repository.save(client);

    Optional<OrganizationClient> found = repository.findById(client.id());

    assertThat(found).isPresent();
    assertThat(found.get().clientId()).isEqualTo("sk_test_id-lookup");
  }

  @Test
  void findByIdIsEmptyForAnUnknownId() {
    assertThat(repository.findById(UUID.randomUUID())).isEmpty();
  }

  @Test
  void findAllByOrganizationIdReturnsOnlyThatOrganizationsOwnClients() {
    UUID organizationId = UUID.randomUUID();
    repository.save(OrganizationClient.register(organizationId, "sk_test_one", "hash", List.of()));
    repository.save(OrganizationClient.register(organizationId, "sk_test_two", "hash", List.of()));
    repository.save(
        OrganizationClient.register(UUID.randomUUID(), "sk_test_other-org", "hash", List.of()));

    List<OrganizationClient> found = repository.findAllByOrganizationId(organizationId);

    assertThat(found)
        .extracting(OrganizationClient::clientId)
        .containsExactlyInAnyOrder("sk_test_one", "sk_test_two");
  }

  @Test
  void deleteAllByOrganizationIdRemovesOnlyThatOrganizationsOwnClients() {
    UUID organizationId = UUID.randomUUID();
    UUID otherOrganizationId = UUID.randomUUID();
    repository.save(
        OrganizationClient.register(organizationId, "sk_test_to-delete", "hash", List.of()));
    repository.save(
        OrganizationClient.register(otherOrganizationId, "sk_test_untouched", "hash", List.of()));

    repository.deleteAllByOrganizationId(organizationId);

    assertThat(repository.findByClientId("sk_test_to-delete")).isEmpty();
    assertThat(repository.findByClientId("sk_test_untouched")).isPresent();
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = SpringDataOrganizationClientJpaRepository.class,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = SpringDataOrganizationClientJpaRepository.class))
  @Import(JpaOrganizationClientRepository.class)
  static class TestConfig {}
}
