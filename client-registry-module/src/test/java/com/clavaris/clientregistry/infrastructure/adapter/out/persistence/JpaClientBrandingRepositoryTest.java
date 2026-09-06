package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.application.usecase.setclientbranding.ClientBrandingRepository;
import com.clavaris.clientregistry.domain.model.ClientBranding;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import java.time.temporal.ChronoUnit;
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
 * Real-Postgres integration test — same rationale/pattern as {@code
 * JpaRedirectPolicyRepositoryTest}. A real {@code oauth_clients} row is required before any {@code
 * ClientBranding} save: {@code client_brandings.oauth_client_id} is a real FK (V20260909100000's
 * own comment — both tables are this module's own migrations, so their ordering is guaranteed).
 */
@SpringBootTest(classes = JpaClientBrandingRepositoryTest.TestConfig.class)
@Testcontainers
class JpaClientBrandingRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private OAuthClientRepository oauthClients;
  @Autowired private ClientBrandingRepository brandings;
  @Autowired private SpringDataClientBrandingJpaRepository springDataRepository;

  @Test
  void savesABrandingAndPersistsItsRealFields() {
    UUID oauthClientId = registerARealOAuthClient();
    ClientBranding branding =
        ClientBranding.define(
            oauthClientId, "https://cdn.example.com/logo.png", "#336699", "JobSeeker");

    brandings.save(branding);

    ClientBrandingEntity persisted = springDataRepository.findById(branding.id()).orElseThrow();
    assertThat(persisted.getOauthClientId()).isEqualTo(oauthClientId);
    assertThat(persisted.getLogoUrl()).isEqualTo("https://cdn.example.com/logo.png");
    assertThat(persisted.getPrimaryColor()).isEqualTo("#336699");
    assertThat(persisted.getApplicationDisplayName()).isEqualTo("JobSeeker");
    // Postgres' timestamptz column stores microsecond precision, not the nanosecond precision
    // Instant.now() carries in memory — an exact isEqualTo would be a coin-flip on every real run.
    assertThat(persisted.getCreatedAt())
        .isCloseTo(branding.createdAt(), within(1, ChronoUnit.MILLIS));
  }

  @Test
  void findByOAuthClientIdReturnsEmptyWhenNoBrandingHasEverBeenSetForThatClient() {
    UUID oauthClientId = registerARealOAuthClient();

    Optional<ClientBranding> found = brandings.findByOAuthClientId(oauthClientId);

    assertThat(found)
        .as("absence must mean \"use Clavaris's own default look\", not an error")
        .isEmpty();
  }

  @Test
  void savingASecondTimeUpdatesTheSameRowRatherThanInsertingASecondOne() {
    UUID oauthClientId = registerARealOAuthClient();
    ClientBranding original = ClientBranding.define(oauthClientId, null, "#111111", null);
    brandings.save(original);

    ClientBranding updated = original.withBranding(null, "#222222", null);
    brandings.save(updated);

    // Scoped to this test's own OAuthClient, not springDataRepository.count() — this test class
    // shares one Postgres container/table across every test method with no per-method cleanup, so
    // a table-wide count would depend on execution order across unrelated tests' own rows.
    long rowsForThisClient =
        springDataRepository.findAll().stream()
            .filter(entity -> entity.getOauthClientId().equals(oauthClientId))
            .count();
    assertThat(rowsForThisClient)
        .as("one OAuthClient must never accumulate two branding rows")
        .isEqualTo(1);
    ClientBranding found = brandings.findByOAuthClientId(oauthClientId).orElseThrow();
    assertThat(found.primaryColor()).contains("#222222");
  }

  private UUID registerARealOAuthClient() {
    OAuthClient client =
        OAuthClient.register(
            UUID.randomUUID(),
            "test_client_" + UUID.randomUUID(),
            "argon2id$hashed",
            List.of("https://app.example.com/callback"),
            List.of("authorization_code"),
            List.of("openid"),
            true,
            List.of());
    oauthClients.save(client);
    return client.id();
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableJpaRepositories(
      basePackageClasses = {
        SpringDataOAuthClientJpaRepository.class,
        SpringDataClientBrandingJpaRepository.class
      },
      includeFilters = {
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = SpringDataOAuthClientJpaRepository.class),
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = SpringDataClientBrandingJpaRepository.class)
      })
  @Import({JpaOAuthClientRepository.class, JpaClientBrandingRepository.class})
  static class TestConfig {}
}
