package com.clavaris.identity.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.SocialIdentityRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SocialIdentity;
import com.clavaris.identity.domain.model.SocialProvider;
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
 * test-strategy.md §2: a real-Postgres integration test for the {@code SocialIdentity} adapter —
 * same rationale as {@code JpaVerificationTokenRepositoryTest}: proves {@code toDomain()} really
 * calls {@code SocialIdentity.reconstitute(...)} and that {@code provider} survives the {@code
 * String}↔enum conversion this entity deliberately does.
 */
@SpringBootTest(classes = JpaSocialIdentityRepositoryTest.TestConfig.class)
@Testcontainers
@Transactional
class JpaSocialIdentityRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private SocialIdentityRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private AccountId accountId;
  private OrganizationId organizationId;

  @BeforeEach
  void seedAnAccount() {
    accountId = new AccountId(UUID.randomUUID());
    organizationId = new OrganizationId(UUID.randomUUID());
    jdbcTemplate.update(
        "insert into accounts (id, organization_id, email, status, created_at) "
            + "values (?, ?, ?, 'ACTIVE', now())",
        accountId.value(),
        organizationId.value(),
        "social-owner-" + accountId.value() + "@example.com");
  }

  @Test
  void savesAndFindsByOrganizationIdProviderAndProviderUserId() {
    SocialIdentity identity =
        SocialIdentity.link(accountId, organizationId, SocialProvider.GOOGLE, "google-sub-1");

    repository.save(identity);
    Optional<SocialIdentity> found =
        repository.findByOrganizationIdAndProviderAndProviderUserId(
            organizationId, SocialProvider.GOOGLE, "google-sub-1");

    assertThat(found).isPresent();
    assertThat(found.get().id()).isEqualTo(identity.id());
    assertThat(found.get().accountId()).isEqualTo(accountId);
    assertThat(found.get().organizationId()).isEqualTo(organizationId);
    assertThat(found.get().provider()).isEqualTo(SocialProvider.GOOGLE);
    assertThat(found.get().providerUserId()).isEqualTo("google-sub-1");
  }

  @Test
  void findIsEmptyForAnUnknownTriple() {
    assertThat(
            repository.findByOrganizationIdAndProviderAndProviderUserId(
                organizationId, SocialProvider.GITHUB, "never-linked"))
        .isEmpty();
  }

  @Test
  void doesNotConfuseTheSameProviderUserIdAcrossDifferentProviders() {
    repository.save(
        SocialIdentity.link(accountId, organizationId, SocialProvider.GOOGLE, "shared-id"));

    assertThat(
            repository.findByOrganizationIdAndProviderAndProviderUserId(
                organizationId, SocialProvider.GITHUB, "shared-id"))
        .as("provider is part of the lookup key, not just providerUserId")
        .isEmpty();
  }

  // CLAUDE.md §5, the exact scenario this fix addresses: the same provider identity linked to two
  // different Organizations' Accounts must resolve independently per Organization, and a lookup
  // scoped to one Organization must never leak the other's Account.
  @Test
  void theSameProviderIdentityLinkedInTwoOrganizationsResolvesIndependently() {
    OrganizationId otherOrganizationId = new OrganizationId(UUID.randomUUID());
    AccountId otherAccountId = new AccountId(UUID.randomUUID());
    jdbcTemplate.update(
        "insert into accounts (id, organization_id, email, status, created_at) "
            + "values (?, ?, ?, 'ACTIVE', now())",
        otherAccountId.value(),
        otherOrganizationId.value(),
        "social-owner-" + otherAccountId.value() + "@example.com");

    repository.save(
        SocialIdentity.link(accountId, organizationId, SocialProvider.GOOGLE, "cross-org-sub"));
    repository.save(
        SocialIdentity.link(
            otherAccountId, otherOrganizationId, SocialProvider.GOOGLE, "cross-org-sub"));

    assertThat(
            repository
                .findByOrganizationIdAndProviderAndProviderUserId(
                    organizationId, SocialProvider.GOOGLE, "cross-org-sub")
                .orElseThrow()
                .accountId())
        .isEqualTo(accountId);
    assertThat(
            repository
                .findByOrganizationIdAndProviderAndProviderUserId(
                    otherOrganizationId, SocialProvider.GOOGLE, "cross-org-sub")
                .orElseThrow()
                .accountId())
        .isEqualTo(otherAccountId);
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = SpringDataSocialIdentityJpaRepository.class,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = SpringDataSocialIdentityJpaRepository.class))
  @Import(JpaSocialIdentityRepository.class)
  static class TestConfig {}
}
