package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.ClientDomainConfigRepository;
import com.clavaris.clientregistry.domain.model.ClientDomainConfig;
import com.clavaris.clientregistry.domain.model.ClientDomainMode;
import com.clavaris.clientregistry.domain.model.DomainVerificationStatus;
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
 * JpaClientBrandingRepositoryTest}. A real {@code oauth_clients} row is required before any {@code
 * ClientDomainConfig} save: {@code client_domain_configs.oauth_client_id} is a real FK
 * (V20260910100000's own comment — both tables are this module's own migrations, so their ordering
 * is guaranteed).
 */
@SpringBootTest(classes = JpaClientDomainConfigRepositoryTest.TestConfig.class)
@Testcontainers
class JpaClientDomainConfigRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private OAuthClientRepository oauthClients;
  @Autowired private ClientDomainConfigRepository domainConfigs;
  @Autowired private SpringDataClientDomainConfigJpaRepository springDataRepository;

  @Test
  void savesADomainConfigAndPersistsItsRealFields() {
    UUID oauthClientId = registerARealOAuthClient();
    ClientDomainConfig config =
        ClientDomainConfig.request(
            oauthClientId, ClientDomainMode.CNAME, "login.example.com", null);

    domainConfigs.save(config);

    ClientDomainConfigEntity persisted = springDataRepository.findById(config.id()).orElseThrow();
    assertThat(persisted.getOauthClientId()).isEqualTo(oauthClientId);
    assertThat(persisted.getMode()).isEqualTo("CNAME");
    assertThat(persisted.getHostname()).isEqualTo("login.example.com");
    assertThat(persisted.getVerificationStatus()).isEqualTo("PENDING");
    assertThat(persisted.getDnsTxtChallengeToken()).isNotBlank();
    // Postgres' timestamptz column stores microsecond precision, not the nanosecond precision
    // Instant.now() carries in memory — an exact isEqualTo would be a coin-flip on every real run.
    assertThat(persisted.getCreatedAt())
        .isCloseTo(config.createdAt(), within(1, ChronoUnit.MILLIS));
  }

  @Test
  void findByOAuthClientIdReturnsEmptyWhenNoDomainHasEverBeenRequestedForThatClient() {
    UUID oauthClientId = registerARealOAuthClient();

    Optional<ClientDomainConfig> found = domainConfigs.findByOAuthClientId(oauthClientId);

    assertThat(found).as("absence must mean \"SHARED mode\", not an error").isEmpty();
  }

  @Test
  void findByHostnameFindsTheOwningClientsConfig() {
    UUID oauthClientId = registerARealOAuthClient();
    ClientDomainConfig config =
        ClientDomainConfig.request(
            oauthClientId, ClientDomainMode.CNAME, "unique-lookup.example.com", null);
    domainConfigs.save(config);

    Optional<ClientDomainConfig> found = domainConfigs.findByHostname("unique-lookup.example.com");

    assertThat(found).isPresent();
    assertThat(found.orElseThrow().oauthClientId()).isEqualTo(oauthClientId);
  }

  @Test
  void savingASecondTimeUpdatesTheSameRowRatherThanInsertingASecondOne() {
    UUID oauthClientId = registerARealOAuthClient();
    ClientDomainConfig original =
        ClientDomainConfig.request(
            oauthClientId, ClientDomainMode.CNAME, "a-example.example.com", null);
    domainConfigs.save(original);

    ClientDomainConfig verified = original.markVerified();
    domainConfigs.save(verified);

    // Scoped to this test's own OAuthClient, not springDataRepository.count() — this test class
    // shares one Postgres container/table across every test method with no per-method cleanup, so
    // a table-wide count would depend on execution order across unrelated tests' own rows.
    long rowsForThisClient =
        springDataRepository.findAll().stream()
            .filter(entity -> entity.getOauthClientId().equals(oauthClientId))
            .count();
    assertThat(rowsForThisClient)
        .as("one OAuthClient must never accumulate two domain-config rows")
        .isEqualTo(1);
    ClientDomainConfig found = domainConfigs.findByOAuthClientId(oauthClientId).orElseThrow();
    assertThat(found.verificationStatus()).contains(DomainVerificationStatus.VERIFIED);
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
        SpringDataClientDomainConfigJpaRepository.class
      },
      includeFilters = {
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = SpringDataOAuthClientJpaRepository.class),
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = SpringDataClientDomainConfigJpaRepository.class)
      })
  @Import({JpaOAuthClientRepository.class, JpaClientDomainConfigRepository.class})
  static class TestConfig {}
}
