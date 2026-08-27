package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.app.support.TestMailSenderConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * TD-SEC-030: real, end-to-end proof that {@code AdminApiSecurityConfig}'s own {@code
 * AntiAbuseRateLimitingFilter} is actually registered, in the right place, on the real {@code
 * /api/v1/admin/**} chain — not just correct in isolation ({@code AntiAbuseRateLimitingFilterTest},
 * a mocked {@code RateLimiter} constructing the filter directly). Same class of gap {@code
 * PlatformTierRateLimitingIntegrationTest} already closed for the platform tier (TD-TEST-003) —
 * before that class, a same-anchor {@code addFilterAfter} collision silently dropped a rate-limit
 * filter from a real chain and nothing caught it until an integration test actually drove a request
 * over the limit.
 *
 * <p>Every attempt below targets a genuinely non-existent resource (a random {@code UUID} or an
 * unknown {@code clientId}) — {@code AntiAbuseRateLimitingFilter} runs before the controller, so
 * the rate-limit counter is consumed regardless of whether the target exists, and a {@code 404}
 * from the real service (not a mock) is the correct "allowed, not found" signal to distinguish from
 * the {@code 429} this class is actually proving.
 *
 * <p>{@code @TestMethodOrder}: the two path-specific rules ({@code
 * admin-api-organizations-delete:client}, {@code admin-api-accounts-delete:client}) run first,
 * tightest-limit-first, since every request against either endpoint also feeds the shared blanket
 * {@code admin-api-post:client} counter for this same client (every rule matching a request is
 * evaluated, not just the first — {@code AntiAbuseRateLimitingFilter}'s own Javadoc). Both stay far
 * under the blanket ceiling on their own (5 + 30 = 35, against a 120 default), so running them
 * before the blanket-focused test leaves that test's own counter still meaningfully exercisable.
 * The blanket test itself uses a generous, no-fixed-bound safety margin instead of assuming a
 * pristine counter — same defensive pattern {@code PlatformTierRateLimitingIntegrationTest}'s own
 * per-IP login test already established for identical shared-Redis-state reasons.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=admin-rate-limit-test-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=an-admin-rate-limit-test-secret"
    })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminApiRateLimitingIntegrationTest extends RedisBackedIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Value("${clavaris.rate-limit.admin-api.organizations-delete.per-client-limit:5}")
  private int organizationsDeletePerClientLimit;

  @Value("${clavaris.rate-limit.admin-api.accounts-delete.per-client-limit:30}")
  private int accountsDeletePerClientLimit;

  @Value("${clavaris.rate-limit.admin-api.per-client-limit:120}")
  private int adminApiPerClientLimit;

  private final HttpClient httpClient = HttpClient.newBuilder().build();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @Order(1)
  void blocksOrganizationsDeleteWith429AfterThePerClientLimitIsExceeded() throws Exception {
    String platformToken = requestPlatformToken("platform:organizations:delete");

    for (int attempt = 1; attempt <= organizationsDeletePerClientLimit; attempt++) {
      assertThat(deleteOrganization(platformToken, UUID.randomUUID()).statusCode())
          .as(
              "attempt %d of %d (the configured limit) must be allowed through to the real"
                  + " service — 404 for an unknown organizationId, not blocked",
              attempt, organizationsDeletePerClientLimit)
          .isEqualTo(404);
    }

    HttpResponse<String> overLimit = deleteOrganization(platformToken, UUID.randomUUID());

    assertThat(overLimit.statusCode())
        .as(
            "the single most destructive admin-API call — the whole point of TD-SEC-030's own"
                + " tightest ceiling — must be blocked before it ever reaches the service")
        .isEqualTo(429);
    assertThat(overLimit.headers().firstValue("Retry-After")).isPresent();
  }

  @Test
  @Order(2)
  void blocksAccountsDeleteWith429AfterThePerClientLimitIsExceeded() throws Exception {
    String platformToken = requestPlatformToken("platform:accounts:delete");

    for (int attempt = 1; attempt <= accountsDeletePerClientLimit; attempt++) {
      assertThat(deleteAccount(platformToken, UUID.randomUUID()).statusCode())
          .as(
              "attempt %d of %d (the configured limit) must be allowed through",
              attempt, accountsDeletePerClientLimit)
          .isEqualTo(404);
    }

    HttpResponse<String> overLimit = deleteAccount(platformToken, UUID.randomUUID());

    assertThat(overLimit.statusCode()).isEqualTo(429);
    assertThat(overLimit.headers().firstValue("Retry-After")).isPresent();
  }

  // No fixed loop bound — see this class's own Javadoc for why a shared-counter-across-tests
  // assumption would be wrong here. Targets platform-clients/*/rotate-secret deliberately: it has
  // no path-specific admin-api rate-limit rule of its own, so only the blanket
  // admin-api-post:client ceiling this test exists to prove can ever produce the 429 observed.
  @Test
  @Order(3)
  void blocksTheBlanketPerClientCeilingRegardlessOfWhichAdminEndpointIsCalled() throws Exception {
    String platformToken = requestPlatformToken("platform:platform-clients:rotate-secret");
    int safetyMargin = organizationsDeletePerClientLimit + accountsDeletePerClientLimit + 10;

    boolean sawTooManyRequests = false;
    for (int attempt = 0;
        attempt < adminApiPerClientLimit + safetyMargin && !sawTooManyRequests;
        attempt++) {
      HttpResponse<String> response =
          rotatePlatformClientSecret(platformToken, "rate-limit-unknown-client-" + attempt);
      assertThat(response.statusCode())
          .as("every attempt must be either 404 (allowed, unknown client) or 429 (blocked)")
          .isIn(404, 429);
      sawTooManyRequests = response.statusCode() == 429;
    }

    assertThat(sawTooManyRequests)
        .as(
            "admin-api-post:client must eventually block a real request on this real chain — the"
                + " exact wiring gap TD-SEC-030 found (zero rate limiting anywhere on this"
                + " surface) before this class existed to prove it's actually closed")
        .isTrue();
  }

  private String requestPlatformToken(final String scope) throws IOException, InterruptedException {
    String basicAuth =
        Base64.getEncoder()
            .encodeToString(
                "admin-rate-limit-test-client:an-admin-rate-limit-test-secret".getBytes());
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/oauth2/token"))
            .header("Authorization", "Basic " + basicAuth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&scope=" + scope))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    JsonNode body = objectMapper.readTree(response.body());
    return body.get("access_token").asString();
  }

  private HttpResponse<String> deleteOrganization(final String platformToken, final UUID id)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/api/v1/admin/organizations/" + id + ":delete"))
            .header("Authorization", "Bearer " + platformToken)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> deleteAccount(final String platformToken, final UUID id)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/api/v1/admin/accounts/" + id + ":delete"))
            .header("Authorization", "Bearer " + platformToken)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> rotatePlatformClientSecret(
      final String platformToken, final String clientId) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(
                baseUri("/api/v1/admin/platform-clients/" + clientId + "/rotate-secret"))
            .header("Authorization", "Bearer " + platformToken)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private URI baseUri(final String path) {
    return URI.create("http://localhost:" + port + path);
  }
}
