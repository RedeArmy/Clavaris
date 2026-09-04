package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.app.support.TestMailSenderConfig;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * SDE-III feature build, 2026-09-04 (Clerk Development/Production instances analysis) — the four
 * phases end to end, against real Postgres and real HTTP, not assumed from the unit tests alone:
 * (1) a brand-new Organization is DEVELOPMENT by default, with a real, explicit, low-capacity
 * {@code RateLimitPolicy} row; (2) a real {@code OAuthClient} registered under it gets a {@code
 * test_}-prefixed {@code clientId}; (3) registering an Account under it never triggers a real
 * outbound verification email, while the {@code VerificationToken} itself is still genuinely
 * created; (4) {@code :create-production-environment} promotes it to a linked {@code PRODUCTION}
 * sibling — no {@code RateLimitPolicy} row (system default applies), and a client registered under
 * *that* Organization gets a {@code live_}-prefixed {@code clientId}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret"
    })
class DevelopmentAndProductionOrganizationEnvironmentsIntegrationTest
    extends RedisBackedIntegrationTest {

  private static final String REDIRECT_URI = "https://client.example.test/callback";
  private static final Pattern CSRF_TOKEN_PATTERN =
      Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");
  private static final String FULL_SCOPE = "platform:organizations:write";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Autowired private PlatformAccountRepository platformAccounts;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private MailSender mailSender;

  private final CookieManager cookieManager = new CookieManager();
  private final HttpClient httpClient =
      HttpClient.newBuilder()
          .cookieHandler(cookieManager)
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void aNewOrganizationIsDevelopmentByDefaultAndCanBePromotedToALinkedProductionSibling()
      throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID developmentOrganizationId = createOrganization(platformToken, "JobSeeker");

    // Phase 3: a real, explicit, low-capacity RateLimitPolicy row exists for the new DEVELOPMENT
    // Organization — not "no row means the system default", the normal state for every other
    // Organization before this feature existed.
    Integer developmentRequestsPerMinute =
        jdbcTemplate.queryForObject(
            "select requests_per_minute from rate_limit_policies where organization_id = ?",
            Integer.class,
            developmentOrganizationId);
    assertThat(developmentRequestsPerMinute).isEqualTo(300);

    // Phase 1 (client prefix): a client registered under a DEVELOPMENT Organization gets a
    // test_-prefixed clientId.
    ClientCredentials developmentClient =
        registerOAuthClient(platformToken, developmentOrganizationId);
    assertThat(developmentClient.clientId()).startsWith("test_");

    // Phase 4: registering an Account under this DEVELOPMENT Organization never triggers a real
    // outbound verification email, but the VerificationToken itself is still genuinely created —
    // the flow completes, it just never leaves this process.
    UUID accountId = registerAccount(developmentOrganizationId, "sandbox-user@example.com");
    verify(mailSender, never()).sendEmailVerification(any(), any(), any());
    Integer verificationTokenRows =
        jdbcTemplate.queryForObject(
            "select count(*) from verification_tokens where account_id = ? and type = ?",
            Integer.class,
            accountId,
            "EMAIL_VERIFICATION");
    assertThat(verificationTokenRows).isEqualTo(1);

    // Phase 2: promote the DEVELOPMENT Organization to a linked PRODUCTION sibling.
    HttpResponse<String> promoteResponse =
        createProductionEnvironment(
            platformToken, developmentOrganizationId, "JobSeeker (production)");
    assertThat(promoteResponse.statusCode()).isEqualTo(201);
    JsonNode promoteBody = objectMapper.readTree(promoteResponse.body());
    UUID productionOrganizationId = UUID.fromString(promoteBody.get("id").asString());
    assertThat(UUID.fromString(promoteBody.get("linkedEnvironmentOrganizationId").asString()))
        .isEqualTo(developmentOrganizationId);

    // The link is discoverable from both sides.
    UUID developmentsOwnLink =
        jdbcTemplate.queryForObject(
            "select linked_environment_organization_id from organizations where id = ?",
            UUID.class,
            developmentOrganizationId);
    assertThat(developmentsOwnLink).isEqualTo(productionOrganizationId);
    String productionEnvironmentColumn =
        jdbcTemplate.queryForObject(
            "select environment from organizations where id = ?",
            String.class,
            productionOrganizationId);
    assertThat(productionEnvironmentColumn).isEqualTo("PRODUCTION");

    // No RateLimitPolicy row for the new PRODUCTION Organization — same "missing row means the
    // system default" behaviour every Organization had before this feature existed.
    Integer productionPolicyRows =
        jdbcTemplate.queryForObject(
            "select count(*) from rate_limit_policies where organization_id = ?",
            Integer.class,
            productionOrganizationId);
    assertThat(productionPolicyRows).isZero();

    // A client registered under the new PRODUCTION Organization gets a live_-prefixed clientId.
    ClientCredentials productionClient =
        registerOAuthClient(platformToken, productionOrganizationId);
    assertThat(productionClient.clientId()).startsWith("live_");
  }

  @Test
  void returns404WhenPromotingAnUnknownOrganization() throws Exception {
    String platformToken = requestPlatformAccessToken();

    HttpResponse<String> response =
        createProductionEnvironment(platformToken, UUID.randomUUID(), "Ghost Co");

    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  void returns409WhenPromotingAnAlreadyProductionOrganization() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID developmentOrganizationId = createOrganization(platformToken, "Acme");
    HttpResponse<String> firstPromotion =
        createProductionEnvironment(platformToken, developmentOrganizationId, "Acme (production)");
    assertThat(firstPromotion.statusCode()).isEqualTo(201);
    UUID productionOrganizationId =
        UUID.fromString(objectMapper.readTree(firstPromotion.body()).get("id").asString());

    // The new PRODUCTION Organization itself is not DEVELOPMENT — cannot be promoted again.
    HttpResponse<String> secondPromotion =
        createProductionEnvironment(platformToken, productionOrganizationId, "Acme (production 2)");

    assertThat(secondPromotion.statusCode()).isEqualTo(409);
  }

  @Test
  void returns409WhenPromotingADevelopmentOrganizationThatAlreadyHasALinkedEnvironment()
      throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID developmentOrganizationId = createOrganization(platformToken, "Beta Inc");
    HttpResponse<String> firstPromotion =
        createProductionEnvironment(
            platformToken, developmentOrganizationId, "Beta Inc (production)");
    assertThat(firstPromotion.statusCode()).isEqualTo(201);

    // The same DEVELOPMENT Organization cannot be promoted a second time.
    HttpResponse<String> secondPromotion =
        createProductionEnvironment(
            platformToken, developmentOrganizationId, "Beta Inc (production again)");

    assertThat(secondPromotion.statusCode()).isEqualTo(409);
  }

  private HttpResponse<String> createProductionEnvironment(
      String platformToken, UUID organizationId, String name)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(
                baseUri(
                    "/api/v1/admin/organizations/"
                        + organizationId
                        + ":create-production-environment"))
            .header("Authorization", "Bearer " + platformToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"" + name + "\"}"))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private String requestPlatformAccessToken() throws IOException, InterruptedException {
    String basicAuth =
        Base64.getEncoder()
            .encodeToString("test-platform-client:a-test-platform-secret".getBytes());
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/oauth2/token"))
            .header("Authorization", "Basic " + basicAuth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "grant_type=client_credentials&scope=" + FULL_SCOPE))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    return objectMapper.readTree(response.body()).get("access_token").asString();
  }

  private UUID createOrganization(String platformToken, String name)
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
                        + registerAPlatformAccount()
                        + "\"}"))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    return UUID.fromString(objectMapper.readTree(response.body()).get("id").asString());
  }

  private UUID registerAPlatformAccount() {
    PlatformAccount account =
        PlatformAccount.register(new Email("owner-" + UUID.randomUUID() + "@example.test"));
    account.attachPasswordCredential("not-a-real-hash-this-test-never-logs-in");
    platformAccounts.save(account);
    return account.id().value();
  }

  private ClientCredentials registerOAuthClient(String platformToken, UUID organizationId)
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
    JsonNode body = objectMapper.readTree(response.body());
    return new ClientCredentials(
        body.get("clientId").asString(), body.get("clientSecret").asString());
  }

  private UUID registerAccount(UUID organizationId, String email)
      throws IOException, InterruptedException {
    HttpRequest getForm =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/register")).GET().build();
    HttpResponse<String> formResponse =
        httpClient.send(getForm, HttpResponse.BodyHandlers.ofString());
    String csrfToken = extractCsrfToken(formResponse.body());

    String password = "a-correct-password";
    String body =
        "_csrf="
            + csrfToken
            + "&email="
            + email
            + "&password="
            + password
            + "&confirmPassword="
            + password;
    HttpRequest register =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/register"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    httpClient.send(register, HttpResponse.BodyHandlers.discarding());

    return jdbcTemplate.queryForObject(
        "select id from accounts where organization_id = ? and email = ?",
        UUID.class,
        organizationId,
        email);
  }

  private static String extractCsrfToken(String html) {
    Matcher matcher = CSRF_TOKEN_PATTERN.matcher(html);
    assertThat(matcher.find()).as("page must render a _csrf hidden input").isTrue();
    return matcher.group(1);
  }

  private URI baseUri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private record ClientCredentials(String clientId, String clientSecret) {}
}
