package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientRepository;
import com.clavaris.clientregistry.domain.model.OrganizationClient;
import com.clavaris.clientregistry.domain.model.PlatformScopes;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import java.time.Instant;
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

  // TD-PERF-020 (keyset revision, 2026-09-14): real-Postgres proof of the paginated sibling,
  // newest-first, forward and backward navigation — same "reconstitute with explicit createdAt
  // instants" discipline organization-module's JpaOrganizationRepositoryTest own identical test
  // already establishes.
  @Test
  void findKeysetPageByOrganizationIdReturnsNewestFirstAndSupportsForwardAndBackwardNavigation() {
    UUID organizationId = UUID.randomUUID();
    Instant now = Instant.now();
    OrganizationClient first =
        reconstituteAt(organizationId, "sk_test_first", now.minusSeconds(20));
    OrganizationClient second =
        reconstituteAt(organizationId, "sk_test_second", now.minusSeconds(10));
    OrganizationClient third = reconstituteAt(organizationId, "sk_test_third", now);
    repository.save(first);
    repository.save(second);
    repository.save(third);
    repository.save(
        OrganizationClient.register(UUID.randomUUID(), "sk_test_other-org", "hash", List.of()));

    KeysetPage<OrganizationClient> firstPage =
        repository.findKeysetPageByOrganizationId(
            organizationId, new KeysetPageRequest(null, null, 2));

    assertThat(firstPage.content())
        .extracting(OrganizationClient::id)
        .containsExactly(third.id(), second.id());
    assertThat(firstPage.hasNext()).isTrue();
    assertThat(firstPage.hasPrevious()).isFalse();

    KeysetPage<OrganizationClient> secondPage =
        repository.findKeysetPageByOrganizationId(
            organizationId, new KeysetPageRequest(firstPage.endCursor(), null, 2));

    assertThat(secondPage.content()).extracting(OrganizationClient::id).containsExactly(first.id());
    assertThat(secondPage.hasNext()).isFalse();
    assertThat(secondPage.hasPrevious()).isTrue();

    KeysetPage<OrganizationClient> backToFirstPage =
        repository.findKeysetPageByOrganizationId(
            organizationId, new KeysetPageRequest(null, secondPage.startCursor(), 2));

    assertThat(backToFirstPage.content())
        .extracting(OrganizationClient::id)
        .containsExactly(third.id(), second.id());
    assertThat(backToFirstPage.hasNext()).isTrue();
    assertThat(backToFirstPage.hasPrevious()).isFalse();
  }

  private static OrganizationClient reconstituteAt(
      final UUID organizationId, final String clientId, final Instant createdAt) {
    return OrganizationClient.reconstitute(
        UUID.randomUUID(), organizationId, clientId, "hash", List.of(), createdAt, true, 0);
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
