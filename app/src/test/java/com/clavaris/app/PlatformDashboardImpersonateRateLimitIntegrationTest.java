package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.app.support.TestMailSenderConfig;
import com.clavaris.identity.application.usecase.registeraccount.PasswordHasher;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * SDE-III review, 2026-09-19: real, end-to-end proof that {@code PlatformDashboardSecurityConfig}'s
 * own new "platform-impersonate:account" rule is actually wired — same TD-TEST-003/TD-SEC-001 bar
 * {@link PlatformTierRateLimitingIntegrationTest} already holds every other {@code /platform/**}
 * rule to. The REST admin API's own {@code admin-api-accounts-impersonate:client} limiter ({@code
 * AdminApiSecurityConfig}) is scoped to a {@code PlatformClient} bearer token this
 * session-authenticated dashboard POST never carries, so it does not cover this path at all — this
 * rule is the only thing that does.
 *
 * <p>Deliberately its OWN test class/Spring context/bootstrap client, not appended to {@link
 * PlatformTierRateLimitingIntegrationTest} — that class's own token-endpoint and platform-login:ip
 * tests deliberately drive their shared counters into 429 territory and leave them poisoned for the
 * rest of the 5-minute fixed window (see that class's own Javadoc); this test's own setup needs a
 * real, un-poisoned {@code /oauth2/token} call and a real, un-poisoned {@code /platform/login}, and
 * no {@code @Order} placement in that shared class could satisfy both without colliding with one or
 * the other. A distinct {@code PLATFORM_BOOTSTRAP_CLIENT_ID} keys every shared counter under a
 * client_id nothing else in the suite ever touches.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=platform-impersonate-rate-limit-test-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-platform-impersonate-rate-limit-test-secret"
    })
class PlatformDashboardImpersonateRateLimitIntegrationTest extends RedisBackedIntegrationTest {

  private static final Pattern CSRF_TOKEN_PATTERN =
      Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");
  private static final String REDIRECT_URI = "https://client.example.test/callback";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Value("${clavaris.rate-limit.platform-impersonate.per-account-limit:10}")
  private int impersonatePerAccountLimit;

  @Autowired private PlatformAccountRepository platformAccounts;
  @Autowired private PasswordHasher passwordHasher;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final HttpClient httpClient =
      HttpClient.newBuilder()
          .cookieHandler(new CookieManager())
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  @Test
  void blocksDashboardImpersonateWith429AfterThePerAccountLimitIsExceeded() throws Exception {
    UUID ownerPlatformAccountId =
        registerAndLogInAVerifiedPlatformAccount(
            "impersonate-rate-limit@example.com", "the-original-password");
    String platformToken = requestPlatformOrganizationsWriteToken();
    UUID organizationId =
        createOrganizationOwnedBy(
            platformToken, "Impersonate Rate Limit Co", ownerPlatformAccountId);
    String clientId = registerOAuthClientForOrganization(platformToken, organizationId);
    UUID accountId =
        registerTenantAccount(
            organizationId, "impersonate-target@example.com", "a-correct-password");
    String dashboardCsrfToken =
        fetchCsrfToken(
            "/platform/dashboard/organizations/" + organizationId + "/users/" + accountId);

    HttpResponse<Void> lastAllowed = null;
    for (int attempt = 1; attempt <= impersonatePerAccountLimit; attempt++) {
      lastAllowed = submitImpersonate(dashboardCsrfToken, organizationId, accountId, clientId);
    }
    assertThat(lastAllowed.statusCode()).isEqualTo(302);

    HttpResponse<Void> overLimit =
        submitImpersonate(dashboardCsrfToken, organizationId, accountId, clientId);

    assertThat(overLimit.statusCode()).isEqualTo(429);
  }

  private UUID registerAndLogInAVerifiedPlatformAccount(String email, String password)
      throws IOException, InterruptedException {
    PlatformAccount account = PlatformAccount.register(new Email(email));
    account.attachPasswordCredential(passwordHasher.hash(password));
    account.verifyEmail();
    platformAccounts.save(account);

    String loginCsrfToken = fetchCsrfToken("/platform/login");
    String body = "_csrf=" + loginCsrfToken + "&email=" + email + "&password=" + password;
    HttpResponse<Void> loginResponse =
        httpClient.send(
            HttpRequest.newBuilder(baseUri("/platform/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.discarding());
    if (loginResponse.statusCode() != 302) {
      throw new IllegalStateException(
          "Fixture login failed, expected 302, got " + loginResponse.statusCode());
    }
    return account.id().value();
  }

  private String requestPlatformOrganizationsWriteToken() throws IOException, InterruptedException {
    String basicAuth =
        Base64.getEncoder()
            .encodeToString(
                "platform-impersonate-rate-limit-test-client:a-platform-impersonate-rate-limit-test-secret"
                    .getBytes());
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/oauth2/token"))
            .header("Authorization", "Basic " + basicAuth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "grant_type=client_credentials&scope=platform:organizations:write"))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    return objectMapper.readTree(response.body()).get("access_token").asString();
  }

  private UUID createOrganizationOwnedBy(
      String platformToken, String name, UUID ownerPlatformAccountId)
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
                        + ownerPlatformAccountId
                        + "\"}"))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    return UUID.fromString(objectMapper.readTree(response.body()).get("id").asString());
  }

  private String registerOAuthClientForOrganization(String platformToken, UUID organizationId)
      throws IOException, InterruptedException {
    String requestBody =
        """
        {
          "redirectUris": ["%s"],
          "allowedGrantTypes": ["authorization_code", "refresh_token"],
          "allowedScopes": ["openid"],
          "requireConsent": false
        }
        """
            .formatted(REDIRECT_URI);
    HttpRequest request =
        HttpRequest.newBuilder(
                baseUri("/api/v1/admin/organizations/" + organizationId + "/clients"))
            .header("Authorization", "Bearer " + platformToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    return objectMapper.readTree(response.body()).get("clientId").asString();
  }

  private UUID registerTenantAccount(UUID organizationId, String email, String password)
      throws IOException, InterruptedException {
    HttpResponse<String> formResponse =
        httpClient.send(
            HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/register")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    String csrfToken = extractCsrfTokenFromHtml(formResponse.body());

    String body =
        "_csrf="
            + csrfToken
            + "&email="
            + email
            + "&password="
            + password
            + "&confirmPassword="
            + password;
    httpClient.send(
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/register"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.discarding());

    return jdbcTemplate.queryForObject(
        "select id from accounts where organization_id = ? and email = ?",
        UUID.class,
        organizationId,
        email);
  }

  private HttpResponse<Void> submitImpersonate(
      String csrfToken, UUID organizationId, UUID accountId, String clientId)
      throws IOException, InterruptedException {
    String body = "_csrf=" + csrfToken + "&clientId=" + clientId;
    return httpClient.send(
        HttpRequest.newBuilder(
                baseUri(
                    "/platform/dashboard/organizations/"
                        + organizationId
                        + "/users/"
                        + accountId
                        + "/impersonate"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.discarding());
  }

  private static String extractCsrfTokenFromHtml(String html) {
    Matcher matcher = CSRF_TOKEN_PATTERN.matcher(html);
    if (!matcher.find()) {
      throw new IllegalStateException("No CSRF token found");
    }
    return matcher.group(1);
  }

  private String fetchCsrfToken(String getPath) throws IOException, InterruptedException {
    HttpResponse<String> form =
        httpClient.send(
            HttpRequest.newBuilder(baseUri(getPath)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    Matcher matcher = CSRF_TOKEN_PATTERN.matcher(form.body());
    if (!matcher.find()) {
      throw new IllegalStateException("No CSRF token found on " + getPath);
    }
    return matcher.group(1);
  }

  private URI baseUri(String path) {
    return URI.create("http://localhost:" + port + path);
  }
}
