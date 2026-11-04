package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.PlatformApiTestSupport;
import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * BR-ORG-06, ADR-0010 (Organization provisioning): the full {@code POST
 * /api/v1/admin/organizations} flow — a real platform-tier token (same issuer {@link
 * PlatformTokenIssuanceIntegrationTest} already exercises), a real HTTP call through {@code
 * AdminApiSecurityConfig}'s scope check, and real rows in both {@code organizations} and {@code
 * signing_keys} — not a mocked assumption that {@code CreateOrganizationSigningKeyBridge} actually
 * wires identity-module's key generation through to a persisted row (test-strategy.md §2, the same
 * "prove it against real code" discipline {@code RegisterAccountIntegrationTest} already applies).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret"
    })
class CreateOrganizationIntegrationTest extends RedisBackedIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformAccountRepository platformAccounts;

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void createsAnOrganizationWithAnActiveSigningKeyInTheSameOperation() throws Exception {
    String accessToken = requestPlatformAccessToken();
    UUID ownerPlatformAccountId = registerAPlatformAccount();

    HttpResponse<String> response =
        createOrganization(accessToken, "JobSeeker", ownerPlatformAccountId);
    assertThat(response.statusCode()).isEqualTo(201);

    JsonNode body = objectMapper.readTree(response.body());
    UUID organizationId = UUID.fromString(body.get("id").asString());
    assertThat(body.get("name").asString()).isEqualTo("JobSeeker");
    assertThat(body.get("createdAt").asString()).isNotBlank();

    JsonNode signingKey = body.get("signingKey");
    assertThat(signingKey.get("kid").asString()).isNotBlank();
    assertThat(signingKey.get("algorithm").asString()).isEqualTo("RS256");

    // BR-ORG-06: the Organization row and its active SigningKey row must both really exist,
    // not just be reflected in the HTTP response — a bug in CreateOrganizationSigningKeyBridge
    // could return a plausible-looking body while never actually reaching identity-module's
    // repository (the exact class of silent bug the spike's own §5.3 finding already taught this
    // codebase to check for explicitly, not assume away).
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from organizations"
                    + " where id = ? and name = ? and owner_platform_account_id = ?",
                Integer.class,
                organizationId,
                "JobSeeker",
                ownerPlatformAccountId))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from signing_keys where organization_id = ? and kid = ? and retired_at is null",
                Integer.class,
                organizationId,
                signingKey.get("kid").asString()))
        .isEqualTo(1);
  }

  @Test
  void rejectsCreationWithNoToken() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/api/v1/admin/organizations"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"NoAuth\"}"))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void rejectsABlankOrganizationName() throws Exception {
    String accessToken = requestPlatformAccessToken();

    HttpResponse<String> response = createOrganization(accessToken, "", UUID.randomUUID());

    assertThat(response.statusCode()).isEqualTo(400);
  }

  // Security finding (SDE-III review, 2026-08-22), regression test for its fix: before this fix,
  // any UUID — real PlatformAccount or not — produced a real Organization with a real signing key.
  // The migration's own comment claimed this was "enforced at the application layer only"; this
  // proves that layer now actually exists, against real code, not a mocked assumption.
  @Test
  void rejectsCreationWithAnOwnerPlatformAccountIdThatDoesNotExist() throws Exception {
    String accessToken = requestPlatformAccessToken();
    UUID nonExistentPlatformAccountId = UUID.randomUUID();

    HttpResponse<String> response =
        createOrganization(accessToken, "Ghost Owner Co", nonExistentPlatformAccountId);

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from organizations where owner_platform_account_id = ?",
                Integer.class,
                nonExistentPlatformAccountId))
        .as("no Organization row must exist for a non-existent owner, not even a partial one")
        .isZero();
  }

  @Test
  void rejectsAMissingOwnerPlatformAccountId() throws Exception {
    String accessToken = requestPlatformAccessToken();
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/api/v1/admin/organizations"))
            .header("Authorization", "Bearer " + accessToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"NoOwner\"}"))
            .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
  }

  private String requestPlatformAccessToken() throws IOException, InterruptedException {
    return PlatformApiTestSupport.platformAccessToken(
        httpClient, objectMapper, baseUri("/oauth2/token"), "platform:organizations:write");
  }

  private HttpResponse<String> createOrganization(
      String accessToken, String name, UUID ownerPlatformAccountId)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/api/v1/admin/organizations"))
            .header("Authorization", "Bearer " + accessToken)
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"name\":\""
                        + name
                        + "\",\"ownerPlatformAccountId\":\""
                        + ownerPlatformAccountId
                        + "\"}"))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private URI baseUri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  // A real PlatformAccount row, written directly through the repository rather than the full
  // /platform/register + /platform/verify-email HTTP flow — this suite only needs the row to
  // exist, not to exercise registration itself (a separate suite's own job).
  private UUID registerAPlatformAccount() {
    return PlatformApiTestSupport.registerAPlatformAccount(platformAccounts);
  }
}
