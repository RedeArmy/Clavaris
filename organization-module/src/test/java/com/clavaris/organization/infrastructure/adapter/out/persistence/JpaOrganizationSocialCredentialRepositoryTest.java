package com.clavaris.organization.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.setorganizationsocialcredential.OrganizationSocialCredentialRepository;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.OrganizationSocialCredential;
import com.clavaris.organization.domain.model.SocialProvider;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ADR-0022: real-Postgres integration test, same pattern as {@code
 * JpaRateLimitPolicyRepositoryTest} — {@code organization_social_credentials.organization_id} is a
 * real FK to {@code organizations}, both this module's own migrations. Class-level
 * {@code @Transactional} (same precedent as {@code JpaAccountRepositoryTest}): {@code
 * deleteByOrganizationIdAndProvider} is a derived delete query, which Spring Data JPA always
 * executes via a load-then-{@code EntityManager.remove()} strategy (so {@code @PreRemove}-shaped
 * lifecycle callbacks still fire) — that needs a real transaction, which the framework's own
 * inherited {@code save()}/{@code findById()} get for free but a custom derived method does not.
 */
@SpringBootTest(classes = JpaOrganizationSocialCredentialRepositoryTest.TestConfig.class)
@Testcontainers
@Transactional
class JpaOrganizationSocialCredentialRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private OrganizationRepository organizations;
  @Autowired private OrganizationSocialCredentialRepository credentials;
  @Autowired private SpringDataOrganizationSocialCredentialJpaRepository springDataRepository;

  @Test
  void savesACredentialAndPersistsItsRealFields() {
    UUID organizationId = registerARealOrganization();
    OrganizationSocialCredential credential =
        OrganizationSocialCredential.define(
            organizationId, SocialProvider.GOOGLE, "google-client-id", "encrypted-secret");

    credentials.save(credential);

    OrganizationSocialCredentialEntity persisted =
        springDataRepository.findById(credential.id()).orElseThrow();
    assertThat(persisted.getOrganizationId()).isEqualTo(organizationId);
    assertThat(persisted.getProvider()).isEqualTo(SocialProvider.GOOGLE);
    assertThat(persisted.getClientId()).isEqualTo("google-client-id");
    assertThat(persisted.getClientSecretEncrypted()).isEqualTo("encrypted-secret");
    assertThat(persisted.getCreatedAt())
        .isCloseTo(credential.createdAt(), within(1, ChronoUnit.MILLIS));
  }

  @Test
  void findByOrganizationIdAndProviderReturnsEmptyWhenNoCredentialHasEverBeenSet() {
    UUID organizationId = registerARealOrganization();

    Optional<OrganizationSocialCredential> found =
        credentials.findByOrganizationIdAndProvider(organizationId, SocialProvider.GOOGLE);

    assertThat(found)
        .as("absence must mean \"use the shared Clavaris app\", not an error")
        .isEmpty();
  }

  @Test
  void savingASecondTimeForTheSameProviderUpdatesTheSameRowRatherThanInsertingASecondOne() {
    UUID organizationId = registerARealOrganization();
    OrganizationSocialCredential original =
        OrganizationSocialCredential.define(
            organizationId, SocialProvider.GOOGLE, "old-id", "old-secret");
    credentials.save(original);

    OrganizationSocialCredential updated = original.withCredential("new-id", "new-secret");
    credentials.save(updated);

    assertThat(springDataRepository.count())
        .as("one (Organization, provider) pair must never accumulate two rows")
        .isEqualTo(1);
    OrganizationSocialCredential found =
        credentials
            .findByOrganizationIdAndProvider(organizationId, SocialProvider.GOOGLE)
            .orElseThrow();
    assertThat(found.clientId()).isEqualTo("new-id");
  }

  @Test
  void findAllByOrganizationIdReturnsEveryProviderIndependently() {
    UUID organizationId = registerARealOrganization();
    credentials.save(
        OrganizationSocialCredential.define(
            organizationId, SocialProvider.GOOGLE, "g-id", "g-secret"));
    credentials.save(
        OrganizationSocialCredential.define(
            organizationId, SocialProvider.GITHUB, "h-id", "h-secret"));

    List<OrganizationSocialCredential> all = credentials.findAllByOrganizationId(organizationId);

    assertThat(all)
        .extracting(OrganizationSocialCredential::provider)
        .containsExactlyInAnyOrder(SocialProvider.GOOGLE, SocialProvider.GITHUB);
  }

  @Test
  void deleteByOrganizationIdAndProviderRemovesOnlyThatProvidersRow() {
    UUID organizationId = registerARealOrganization();
    credentials.save(
        OrganizationSocialCredential.define(
            organizationId, SocialProvider.GOOGLE, "g-id", "g-secret"));
    credentials.save(
        OrganizationSocialCredential.define(
            organizationId, SocialProvider.GITHUB, "h-id", "h-secret"));

    credentials.deleteByOrganizationIdAndProvider(organizationId, SocialProvider.GOOGLE);

    assertThat(credentials.findByOrganizationIdAndProvider(organizationId, SocialProvider.GOOGLE))
        .isEmpty();
    assertThat(credentials.findByOrganizationIdAndProvider(organizationId, SocialProvider.GITHUB))
        .isPresent();
  }

  private UUID registerARealOrganization() {
    Organization organization = Organization.register("Social Credential Co", UUID.randomUUID());
    organizations.save(organization);
    return organization.id();
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = {
        SpringDataOrganizationJpaRepository.class,
        SpringDataOrganizationSocialCredentialJpaRepository.class
      })
  @Import({JpaOrganizationRepository.class, JpaOrganizationSocialCredentialRepository.class})
  static class TestConfig {}
}
