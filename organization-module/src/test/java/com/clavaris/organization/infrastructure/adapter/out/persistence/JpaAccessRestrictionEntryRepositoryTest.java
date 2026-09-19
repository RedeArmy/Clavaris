package com.clavaris.organization.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.organization.application.usecase.addaccessrestrictionentry.AccessRestrictionEntryRepository;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.AccessRestrictionEntry;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.RestrictionType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * SDE-III review, 2026-09-19 — real-Postgres integration test, same pattern as
 * JpaAccountAuthenticationPolicyRepositoryTest: {@code access_restriction_entries.organization_id}
 * is a real FK, so every test registers a real Organization first.
 */
@SpringBootTest(classes = JpaAccessRestrictionEntryRepositoryTest.TestConfig.class)
@Testcontainers
class JpaAccessRestrictionEntryRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private OrganizationRepository organizations;
  @Autowired private AccessRestrictionEntryRepository entries;

  private UUID registerARealOrganization() {
    Organization organization = Organization.register("Restrictions Co", UUID.randomUUID());
    organizations.save(organization);
    return organization.id();
  }

  @Test
  void savesAndFindsAnEntryByIdWithItsRealFields() {
    UUID organizationId = registerARealOrganization();
    AccessRestrictionEntry entry =
        AccessRestrictionEntry.create(
            organizationId, RestrictionType.BLOCKLIST, "blocked@example.com");

    entries.save(entry);

    AccessRestrictionEntry found = entries.findById(entry.id()).orElseThrow();
    assertThat(found.organizationId()).isEqualTo(organizationId);
    assertThat(found.type()).isEqualTo(RestrictionType.BLOCKLIST);
    assertThat(found.identifier()).isEqualTo("blocked@example.com");
  }

  @Test
  void findByIdReturnsEmptyWhenNoSuchEntryExists() {
    assertThat(entries.findById(UUID.randomUUID())).isEmpty();
  }

  @Test
  void findAllByOrganizationIdIsScopedToOneOrganizationOnly() {
    UUID organizationId = registerARealOrganization();
    UUID otherOrganizationId = registerARealOrganization();
    entries.save(
        AccessRestrictionEntry.create(organizationId, RestrictionType.BLOCKLIST, "a@example.com"));
    entries.save(
        AccessRestrictionEntry.create(
            otherOrganizationId, RestrictionType.BLOCKLIST, "b@example.com"));

    List<AccessRestrictionEntry> found = entries.findAllByOrganizationId(organizationId);

    assertThat(found)
        .extracting(AccessRestrictionEntry::identifier)
        .containsExactly("a@example.com");
  }

  @Test
  void existsByOrganizationIdAndIdentifierIsTrueOnlyForARealMatchInThatOrganization() {
    UUID organizationId = registerARealOrganization();
    UUID otherOrganizationId = registerARealOrganization();
    entries.save(
        AccessRestrictionEntry.create(
            organizationId, RestrictionType.BLOCKLIST, "blocked@example.com"));

    assertThat(entries.existsByOrganizationIdAndIdentifier(organizationId, "blocked@example.com"))
        .isTrue();
    assertThat(
            entries.existsByOrganizationIdAndIdentifier(otherOrganizationId, "blocked@example.com"))
        .as("BR-ORG-01-shaped isolation: same identifier, different Organization, no match")
        .isFalse();
    assertThat(
            entries.existsByOrganizationIdAndIdentifier(organizationId, "someone-else@example.com"))
        .isFalse();
  }

  @Test
  void deleteByIdRemovesTheRow() {
    UUID organizationId = registerARealOrganization();
    AccessRestrictionEntry entry =
        AccessRestrictionEntry.create(organizationId, RestrictionType.ALLOWLIST, "@example.com");
    entries.save(entry);

    entries.deleteById(entry.id());

    assertThat(entries.findById(entry.id())).isEmpty();
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = {
        SpringDataOrganizationJpaRepository.class,
        SpringDataAccessRestrictionEntryJpaRepository.class
      })
  @Import({JpaOrganizationRepository.class, JpaAccessRestrictionEntryRepository.class})
  static class TestConfig {}
}
