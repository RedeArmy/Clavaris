package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.application.usecase.setredirectpolicyforclient.RedirectPolicyRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.clientregistry.domain.model.RedirectPolicy;
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
 * JpaRateLimitPolicyRepositoryTest}. A real {@code oauth_clients} row is required before any {@code
 * RedirectPolicy} save: {@code redirect_policies.oauth_client_id} is a real FK (V20260906120000's
 * own comment — both tables are this module's own migrations, so their ordering is guaranteed).
 */
@SpringBootTest(classes = JpaRedirectPolicyRepositoryTest.TestConfig.class)
@Testcontainers
class JpaRedirectPolicyRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private OAuthClientRepository oauthClients;
  @Autowired private RedirectPolicyRepository policies;
  @Autowired private SpringDataRedirectPolicyJpaRepository springDataRepository;

  @Test
  void savesAPolicyAndPersistsItsRealFields() {
    UUID oauthClientId = registerARealOAuthClient();
    RedirectPolicy policy =
        RedirectPolicy.define(
            oauthClientId,
            "https://app.example.com/callback",
            "https://app.example.com/callback",
            null,
            null);

    policies.save(policy);

    RedirectPolicyEntity persisted = springDataRepository.findById(policy.id()).orElseThrow();
    assertThat(persisted.getOauthClientId()).isEqualTo(oauthClientId);
    assertThat(persisted.getFallbackSignInRedirectUrl())
        .isEqualTo("https://app.example.com/callback");
    assertThat(persisted.getForceSignInRedirectUrl()).isNull();
    // Postgres' timestamptz column stores microsecond precision, not the nanosecond precision
    // Instant.now() carries in memory — an exact isEqualTo would be a coin-flip on every real run.
    assertThat(persisted.getCreatedAt())
        .isCloseTo(policy.createdAt(), within(1, ChronoUnit.MILLIS));
  }

  @Test
  void findByOAuthClientIdReturnsEmptyWhenNoPolicyHasEverBeenSetForThatClient() {
    UUID oauthClientId = registerARealOAuthClient();

    Optional<RedirectPolicy> found = policies.findByOAuthClientId(oauthClientId);

    assertThat(found)
        .as("absence must mean \"fall through to the platform's own default\", not an error")
        .isEmpty();
  }

  @Test
  void savingASecondTimeUpdatesTheSameRowRatherThanInsertingASecondOne() {
    UUID oauthClientId = registerARealOAuthClient();
    RedirectPolicy original =
        RedirectPolicy.define(oauthClientId, "https://app.example.com/a", null, null, null);
    policies.save(original);

    RedirectPolicy updated = original.withUrls("https://app.example.com/b", null, null, null);
    policies.save(updated);

    assertThat(springDataRepository.count())
        .as("one OAuthClient must never accumulate two policy rows")
        .isEqualTo(1);
    RedirectPolicy found = policies.findByOAuthClientId(oauthClientId).orElseThrow();
    assertThat(found.fallbackSignInRedirectUrl()).contains("https://app.example.com/b");
  }

  private UUID registerARealOAuthClient() {
    OAuthClient client =
        OAuthClient.register(
            UUID.randomUUID(),
            "test_client_" + UUID.randomUUID(),
            "argon2id$hashed",
            List.of(
                "https://app.example.com/callback",
                "https://app.example.com/a",
                "https://app.example.com/b"),
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
        SpringDataRedirectPolicyJpaRepository.class
      },
      includeFilters = {
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = SpringDataOAuthClientJpaRepository.class),
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = SpringDataRedirectPolicyJpaRepository.class)
      })
  @Import({JpaOAuthClientRepository.class, JpaRedirectPolicyRepository.class})
  static class TestConfig {}
}
