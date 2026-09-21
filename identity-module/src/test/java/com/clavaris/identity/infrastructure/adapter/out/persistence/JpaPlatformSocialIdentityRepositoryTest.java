package com.clavaris.identity.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.application.usecase.authenticateplatformaccountwithsocialprovider.PlatformSocialIdentityRepository;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.domain.model.PlatformSocialIdentity;
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
 * test-strategy.md §2: a real-Postgres integration test for the {@code PlatformSocialIdentity}
 * adapter — {@link JpaSocialIdentityRepositoryTest}'s own platform-tier sibling, same rationale:
 * proves {@code toDomain()} really calls {@code PlatformSocialIdentity.reconstitute(...)} and that
 * {@code provider} survives the {@code String}↔enum conversion this entity deliberately does.
 */
@SpringBootTest(classes = JpaPlatformSocialIdentityRepositoryTest.TestConfig.class)
@Testcontainers
@Transactional
class JpaPlatformSocialIdentityRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private PlatformSocialIdentityRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private PlatformAccountId platformAccountId;

  @BeforeEach
  void seedAPlatformAccount() {
    platformAccountId = new PlatformAccountId(UUID.randomUUID());
    jdbcTemplate.update(
        "insert into platform_accounts (id, email, status, created_at) values (?, ?, 'ACTIVE', now())",
        platformAccountId.value(),
        "social-owner-" + platformAccountId.value() + "@example.com");
  }

  @Test
  void savesAndFindsByProviderAndProviderUserId() {
    PlatformSocialIdentity identity =
        PlatformSocialIdentity.link(platformAccountId, SocialProvider.GOOGLE, "google-sub-1");

    repository.save(identity);
    Optional<PlatformSocialIdentity> found =
        repository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "google-sub-1");

    assertThat(found).isPresent();
    assertThat(found.get().id()).isEqualTo(identity.id());
    assertThat(found.get().platformAccountId()).isEqualTo(platformAccountId);
    assertThat(found.get().provider()).isEqualTo(SocialProvider.GOOGLE);
    assertThat(found.get().providerUserId()).isEqualTo("google-sub-1");
  }

  @Test
  void findIsEmptyForAnUnknownPair() {
    assertThat(repository.findByProviderAndProviderUserId(SocialProvider.GITHUB, "never-linked"))
        .isEmpty();
  }

  @Test
  void doesNotConfuseTheSameProviderUserIdAcrossDifferentProviders() {
    repository.save(
        PlatformSocialIdentity.link(platformAccountId, SocialProvider.GOOGLE, "shared-id"));

    assertThat(repository.findByProviderAndProviderUserId(SocialProvider.GITHUB, "shared-id"))
        .as("provider is part of the lookup key, not just providerUserId")
        .isEmpty();
  }

  @Test
  void findAllByPlatformAccountIdReturnsEveryLinkedProviderForThisAccountOnly() {
    PlatformAccountId otherPlatformAccountId = new PlatformAccountId(UUID.randomUUID());
    jdbcTemplate.update(
        "insert into platform_accounts (id, email, status, created_at) values (?, ?, 'ACTIVE', now())",
        otherPlatformAccountId.value(),
        "other-owner-" + otherPlatformAccountId.value() + "@example.com");
    PlatformSocialIdentity google =
        PlatformSocialIdentity.link(platformAccountId, SocialProvider.GOOGLE, "google-sub-2");
    PlatformSocialIdentity github =
        PlatformSocialIdentity.link(platformAccountId, SocialProvider.GITHUB, "github-sub-2");
    PlatformSocialIdentity someoneElses =
        PlatformSocialIdentity.link(otherPlatformAccountId, SocialProvider.GOOGLE, "not-mine");
    repository.save(google);
    repository.save(github);
    repository.save(someoneElses);

    assertThat(repository.findAllByPlatformAccountId(platformAccountId))
        .extracting(PlatformSocialIdentity::id)
        .containsExactlyInAnyOrder(google.id(), github.id());
  }

  @Test
  void findAllByPlatformAccountIdReturnsEmptyForAnAccountWithNoLinkedProviders() {
    assertThat(repository.findAllByPlatformAccountId(platformAccountId)).isEmpty();
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = SpringDataPlatformSocialIdentityJpaRepository.class,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = SpringDataPlatformSocialIdentityJpaRepository.class))
  @Import(JpaPlatformSocialIdentityRepository.class)
  static class TestConfig {}
}
