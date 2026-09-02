package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * test-strategy.md §2: a real-Postgres integration test for the tenant-scoped OAuth client adapter
 * — proves {@code toDomain()} really calls {@code OAuthClient.reconstitute(...)} and that all
 * JSON-serialized list columns (redirect_uris, allowed_grant_types, allowed_scopes,
 * post_logout_redirect_uris) round-trip correctly against the real migrated schema.
 *
 * <p>Deliberately NOT {@code @DataJpaTest} — see {@code JpaPlatformClientRepositoryTest}'s own
 * Javadoc for the full finding (removed in Spring Boot 4.1). {@code @Import}, not
 * {@code @ComponentScan}, for the same reason that test's {@code TestConfig} now uses it too: this
 * package holds both tests' nested {@code @Configuration} classes.
 */
@SpringBootTest(classes = JpaOAuthClientRepositoryTest.TestConfig.class)
@Testcontainers
class JpaOAuthClientRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private OAuthClientRepository repository;

  @Test
  void savesAndReadsBackAnOAuthClient_reconstituteKeepsTheRealPersistedId() {
    UUID organizationId = UUID.randomUUID();
    OAuthClient client =
        OAuthClient.register(
            organizationId,
            "a-client-id",
            "argon2id$hashed",
            List.of("https://jobseeker.example.com/callback"),
            List.of("authorization_code", "refresh_token"),
            List.of("openid", "profile"),
            false,
            List.of("https://jobseeker.example.com/logged-out"));

    repository.save(client);
    Optional<OAuthClient> found = repository.findByClientId("a-client-id");

    assertThat(found).isPresent();
    assertThat(found.get().id()).isEqualTo(client.id());
    assertThat(found.get().organizationId()).isEqualTo(organizationId);
    assertThat(found.get().clientSecretHash()).isEqualTo("argon2id$hashed");
    assertThat(found.get().redirectUris())
        .containsExactly("https://jobseeker.example.com/callback");
    assertThat(found.get().allowedGrantTypes())
        .containsExactly("authorization_code", "refresh_token");
    assertThat(found.get().allowedScopes()).containsExactly("openid", "profile");
    // TD-SEC-026/ADR-0017: the boolean column must round-trip too — false chosen deliberately
    // here (not the DB column's own default true) so this test cannot pass by accident if the
    // column were never actually wired through JpaOAuthClientRepository at all.
    assertThat(found.get().requireConsent()).isFalse();
    // TD-FUT-018: same "prove it's really wired, not just present" bar as requireConsent above.
    assertThat(found.get().postLogoutRedirectUris())
        .containsExactly("https://jobseeker.example.com/logged-out");
  }

  @Test
  void findByClientIdIsEmptyForAnUnknownClient() {
    assertThat(repository.findByClientId(UUID.randomUUID().toString())).isEmpty();
  }

  @Test
  void findByIdReturnsTheSameClientLookedUpByItsOwnPersistedId() {
    // TD-SEC-010: this is the exact lookup OrganizationRegisteredClientRepository.findById now
    // performs on JdbcOAuth2AuthorizationService's behalf (TD-SEC-003) when reloading a persisted
    // OAuth2Authorization row.
    OAuthClient client =
        OAuthClient.register(
            UUID.randomUUID(),
            "another-client-id",
            "argon2id$hashed",
            List.of("https://jobseeker.example.com/callback"),
            List.of("authorization_code"),
            List.of("openid"),
            true,
            List.of());
    repository.save(client);

    Optional<OAuthClient> found = repository.findById(client.id());

    assertThat(found).isPresent();
    assertThat(found.get().clientId()).isEqualTo("another-client-id");
  }

  @Test
  void findByIdIsEmptyForAnUnknownId() {
    assertThat(repository.findById(UUID.randomUUID())).isEmpty();
  }

  // @Import, not @ComponentScan — see JpaPlatformClientRepositoryTest's own TestConfig comment
  // for why: this package also holds that test's nested TestConfig, and scanning the whole
  // package here would pick it up too, double-registering Spring Data repositories across both
  // contexts (confirmed live, identity-module).
  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = SpringDataOAuthClientJpaRepository.class,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = SpringDataOAuthClientJpaRepository.class))
  @Import(JpaOAuthClientRepository.class)
  static class TestConfig {}
}
