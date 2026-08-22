package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.app.support.TestMailSenderConfig;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccount;
import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR-0010 §6/BR-ID-06/BR-ORG-05: end-to-end proof that both rate-limiting layers actually engage
 * on the real, wired filter chains — real HTTP, real Redis ({@link RedisBackedIntegrationTest}'s
 * shared Testcontainers instance, same {@code redis:7} image {@code docker-compose.yml} runs), real
 * Postgres. {@link RedisFixedWindowRateLimiterTest}/{@code AntiAbuseRateLimitingFilterTest}/{@code
 * OrganizationCapacityRateLimitingFilterTest} already prove the mechanism in isolation; this class
 * proves the actual wiring — the real {@code clavaris.rate-limit.*} defaults, against the real
 * security chains, not a hand-built filter instance.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=rate-limit-test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-rate-limit-test-platform-secret"
    })
class RateLimitingIntegrationTest extends RedisBackedIntegrationTest {

  private static final Pattern CSRF_TOKEN_PATTERN =
      Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Value("${clavaris.rate-limit.login.per-account-limit:10}")
  private int loginPerAccountLimit;

  @Value("${clavaris.rate-limit.token.per-client-limit:20}")
  private int tokenPerClientLimit;

  @Autowired private PlatformAccountRepository platformAccounts;

  private final HttpClient httpClient =
      HttpClient.newBuilder()
          .cookieHandler(new CookieManager())
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void blocksLoginWith429AfterThePerAccountAntiAbuseLimitIsExceeded() throws Exception {
    UUID organizationId = UUID.randomUUID();
    String email = "victim@example.com";
    String csrfToken = fetchLoginCsrfToken(organizationId);

    // One request under the limit must behave normally (a real login-failure re-render, 200 —
    // LoginController's own InvalidCredentialsException path, never a 429).
    HttpResponse<String> underLimit = submitLogin(organizationId, csrfToken, email);
    assertThat(underLimit.statusCode()).isEqualTo(200);

    // Exhaust the remaining budget in this same 5-minute window. tryConsume allows
    // currentCount <= limit, so exactly loginPerAccountLimit requests must land (not
    // loginPerAccountLimit - 1) before the next one is the first to exceed it.
    HttpResponse<String> lastAllowed = underLimit;
    for (int attempt = 2; attempt <= loginPerAccountLimit; attempt++) {
      lastAllowed = submitLogin(organizationId, csrfToken, email);
    }
    assertThat(lastAllowed.statusCode())
        .as("attempts up to and including the configured limit must still be allowed")
        .isEqualTo(200);

    HttpResponse<String> overLimit = submitLogin(organizationId, csrfToken, email);

    assertThat(overLimit.statusCode()).isEqualTo(429);
    assertThat(overLimit.headers().firstValue("Retry-After")).isPresent();
  }

  @Test
  void aDifferentAccountInTheSameOrganizationHasItsOwnIndependentCounter() throws Exception {
    UUID organizationId = UUID.randomUUID();
    String csrfToken = fetchLoginCsrfToken(organizationId);
    for (int attempt = 1; attempt <= loginPerAccountLimit; attempt++) {
      submitLogin(organizationId, csrfToken, "exhausted@example.com");
    }
    // The exhausted account above must not be able to reach the endpoint at all any more...
    assertThat(submitLogin(organizationId, csrfToken, "exhausted@example.com").statusCode())
        .isEqualTo(429);

    // ...but a completely different account in the very same Organization is unaffected.
    HttpResponse<String> freshAccount = submitLogin(organizationId, csrfToken, "fresh@example.com");

    assertThat(freshAccount.statusCode())
        .as("BR-ID-06: one account's exhausted counter must never throttle an unrelated account")
        .isEqualTo(200);
  }

  @Test
  void refreshTokenGrantIsNeverThrottledByTheStrictNonRefreshClientLimit() throws Exception {
    // BR-ID-06's own explicit mandate: "must never throttle a legitimate token-refresh cycle."
    // Deliberately presents a bogus refresh_token value on every call — this test only needs to
    // prove the *rate-limit layer* never returns 429 for this grant type, not that the token
    // itself is valid (RateLimitRule's own grant_type-based routing doesn't care either way).
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Refresh Limit Co");
    ClientCredentials client = registerOAuthClient(platformToken, organizationId);

    for (int attempt = 1; attempt <= tokenPerClientLimit + 5; attempt++) {
      HttpResponse<String> response = attemptRefresh(organizationId, client);
      assertThat(response.statusCode())
          .as(
              "attempt %d (past the strict non-refresh limit of %d) must never be 429",
              attempt, tokenPerClientLimit)
          .isNotEqualTo(429);
    }
  }

  @Test
  void theCapacityLayerBlocksAcrossDifferentAccountsOnceTheOrganizationsOwnCeilingIsExceeded()
      throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Low Capacity Co");
    // 4, not 3: OrganizationCapacityRateLimitingFilter aggregates every request under
    // /o/{organizationId}/... regardless of method — the one CSRF-fetching GET below already
    // consumes the first unit of budget, same as any of the three POSTs that follow it would.
    setRateLimitPolicy(platformToken, organizationId, 4);
    String csrfToken = fetchLoginCsrfToken(organizationId);

    // Three different accounts, each attempting exactly once — none would trip the per-account
    // anti-abuse layer (each account's own counter is at 1), isolating the capacity layer's own
    // aggregate ceiling as the only thing that could block the 4th.
    for (int account = 1; account <= 3; account++) {
      HttpResponse<String> response =
          submitLogin(organizationId, csrfToken, "account" + account + "@example.com");
      assertThat(response.statusCode())
          .as("request %d is within the tuned capacity ceiling", account)
          .isEqualTo(200);
    }

    HttpResponse<String> fourthAccount =
        submitLogin(organizationId, csrfToken, "account4@example.com");

    assertThat(fourthAccount.statusCode())
        .as(
            "ADR-0010 §6.2: the aggregate ceiling blocks this request even though it's a "
                + "brand-new account with its own, still-fresh per-account counter")
        .isEqualTo(429);
  }

  // Fetched once per test, not once per attempt: the CSRF token stays valid for the whole
  // session regardless of how many failed logins follow (only a *successful* login rotates the
  // session), and fetching it just once means every OrganizationCapacityRateLimitingFilter
  // budget calculation in this file only has to account for one extra GET, not one per attempt.
  private String fetchLoginCsrfToken(final UUID organizationId)
      throws IOException, InterruptedException {
    HttpResponse<String> form =
        httpClient.send(
            HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/login")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    return extractCsrfToken(form.body());
  }

  private HttpResponse<String> submitLogin(
      final UUID organizationId, final String csrfToken, final String email)
      throws IOException, InterruptedException {
    String body = "_csrf=" + csrfToken + "&email=" + email + "&password=wrong-password";
    return httpClient.send(
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/login"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> attemptRefresh(
      final UUID organizationId, final ClientCredentials client)
      throws IOException, InterruptedException {
    String basicAuth =
        Base64.getEncoder()
            .encodeToString((client.clientId() + ":" + client.clientSecret()).getBytes());
    String body = "grant_type=refresh_token&refresh_token=bogus-token-value";
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/oauth2/token"))
            .header("Authorization", "Basic " + basicAuth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private void setRateLimitPolicy(
      final String platformToken, final UUID organizationId, final int requestsPerMinute)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(
                baseUri("/api/v1/admin/organizations/" + organizationId + "/rate-limit-policy"))
            .header("Authorization", "Bearer " + platformToken)
            .header("Content-Type", "application/json")
            .PUT(
                HttpRequest.BodyPublishers.ofString(
                    "{\"requestsPerMinute\":" + requestsPerMinute + "}"))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
  }

  private String requestPlatformAccessToken() throws IOException, InterruptedException {
    String basicAuth =
        Base64.getEncoder()
            .encodeToString(
                "rate-limit-test-platform-client:a-rate-limit-test-platform-secret".getBytes());
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/oauth2/token"))
            .header("Authorization", "Basic " + basicAuth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    // Real bug, confirmed live: a raw, unencoded space between the two scopes
                    // here (application/x-www-form-urlencoded requires "+" or "%20") left SAS
                    // unable to parse the second scope at all, silently granting a token with
                    // only the first — every downstream call needing the second scope then failed
                    // with 403, surfacing as a confusing NPE two calls later, not an obvious
                    // auth error at the source.
                    "grant_type=client_credentials&scope=platform:organizations:write"
                        + "+platform:rate-limit-policy:write"))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode())
        .as("platform token request failed: " + response.body())
        .isEqualTo(200);
    return objectMapper.readTree(response.body()).get("access_token").asString();
  }

  private UUID createOrganization(final String platformToken, final String name)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/api/v1/admin/organizations"))
            .header("Authorization", "Bearer " + platformToken)
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"name\":\""
                        + name
                        + "\",\"ownerPlatformAccountId\":\""
                        // Real bug, confirmed live: CreateOrganizationController returns a bare,
                        // bodyless 404 (ResponseEntity.notFound().build()) whenever
                        // ownerPlatformAccountId doesn't reference a real PlatformAccount row —
                        // a random UUID here always 404s; a real row is required, same pattern
                        // CreateOrganizationIntegrationTest already establishes.
                        + registerAPlatformAccount()
                        + "\"}"))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode())
        .as("organization creation failed: " + response.body())
        .isEqualTo(201);
    return UUID.fromString(objectMapper.readTree(response.body()).get("id").asString());
  }

  // A real PlatformAccount row, written directly through the repository rather than the full
  // /platform/register + /platform/verify-email HTTP flow — this suite only needs the row to
  // exist for CreateOrganizationController's owner check, not to exercise registration itself.
  private UUID registerAPlatformAccount() {
    PlatformAccount account =
        PlatformAccount.register(new Email("owner-" + UUID.randomUUID() + "@example.test"));
    // JpaPlatformAccountRepository.save() rejects the credential-less intermediate state
    // PlatformAccount.register() itself allows — a real hash is irrelevant here, this suite
    // never logs in as this account, only needs its row to exist for the owner check.
    account.attachPasswordCredential("not-a-real-hash-this-test-never-logs-in");
    platformAccounts.save(account);
    return account.id().value();
  }

  private ClientCredentials registerOAuthClient(
      final String platformToken, final UUID organizationId)
      throws IOException, InterruptedException {
    String requestBody =
        """
        {
          "redirectUris": ["https://client.example.test/callback"],
          "allowedGrantTypes": ["authorization_code", "refresh_token"],
          "allowedScopes": ["openid"]
        }
        """;
    HttpRequest request =
        HttpRequest.newBuilder(
                baseUri("/api/v1/admin/organizations/" + organizationId + "/clients"))
            .header("Authorization", "Bearer " + platformToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode())
        .as("client registration failed: " + response.body())
        .isEqualTo(201);
    var body = objectMapper.readTree(response.body());
    return new ClientCredentials(
        body.get("clientId").asString(), body.get("clientSecret").asString());
  }

  private static String extractCsrfToken(final String html) {
    Matcher matcher = CSRF_TOKEN_PATTERN.matcher(html);
    assertThat(matcher.find()).as("page must render a _csrf hidden input").isTrue();
    return matcher.group(1);
  }

  private URI baseUri(final String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private record ClientCredentials(String clientId, String clientSecret) {}
}
