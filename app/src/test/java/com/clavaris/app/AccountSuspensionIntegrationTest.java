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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
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
 * Reversible ban follow-up: {@code POST /api/v1/admin/accounts/{id}:suspend} must kill an
 * already-live session/token immediately (not merely block future logins) and {@code POST
 * /api/v1/admin/accounts/{id}:reactivate} must reverse it — same "confirmed live, not assumed" bar
 * as {@code DeleteAccountIntegrationTest}. Builds a real Authorization Code exchange first, exactly
 * like that test, so there is a real, Account-bound {@code oauth2_authorization} row to prove gets
 * revoked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret"
    })
class AccountSuspensionIntegrationTest extends RedisBackedIntegrationTest {

  private static final String REDIRECT_URI = "https://client.example.test/callback";
  private static final Pattern CSRF_TOKEN_PATTERN =
      Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");
  private static final String FULL_SCOPE = "platform:organizations:write platform:accounts:suspend";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Autowired private PlatformAccountRepository platformAccounts;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final CookieManager cookieManager = new CookieManager();
  private final HttpClient httpClient =
      HttpClient.newBuilder()
          .cookieHandler(cookieManager)
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final SecureRandom secureRandom = new SecureRandom();

  @Test
  void suspensionRevokesLiveTokensAndBlocksLoginImmediately_reactivationReversesIt()
      throws Exception {
    String platformToken = requestPlatformAccessToken(FULL_SCOPE);
    UUID organizationId = createOrganization(platformToken, "Suspension Co");
    ClientCredentials client = registerOAuthClient(platformToken, organizationId);
    String email = "suspend-me@example.com";
    String password = "a-correct-password";
    UUID accountId = registerAccount(organizationId, email, password);

    String codeVerifier = generateCodeVerifier();
    String codeChallenge = deriveCodeChallenge(codeVerifier);
    getAuthorize(organizationId, client.clientId(), codeChallenge);
    String loginCsrfToken = fetchLoginCsrfToken(organizationId);
    HttpResponse<Void> loginResponse = submitLogin(organizationId, loginCsrfToken, email, password);
    String backToAuthorize = loginResponse.headers().firstValue("Location").orElseThrow();
    HttpResponse<Void> authorizedResponse = getDiscardingBodyAbsolute(backToAuthorize);
    String redirectWithCode = authorizedResponse.headers().firstValue("Location").orElseThrow();
    String code = queryParam(redirectWithCode, "code");
    HttpResponse<String> tokenResponse = exchangeCode(organizationId, client, code, codeVerifier);
    assertThat(tokenResponse.statusCode()).isEqualTo(200);
    assertThat(countOauth2AuthorizationRowsForPrincipal(accountId))
        .as("a real, live authorization row must exist before suspension")
        .isGreaterThan(0);

    HttpResponse<String> suspendResponse = suspendAccount(platformToken, accountId);
    assertThat(suspendResponse.statusCode()).isEqualTo(204);

    assertThat(countOauth2AuthorizationRowsForPrincipal(accountId))
        .as("every live token must be revoked immediately on suspension")
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "select status from accounts where id = ?", String.class, accountId))
        .isEqualTo("SUSPENDED");

    // Future logins must fail even with the exactly-correct password — anti-enumeration: a
    // generic re-rendered login form, never a distinguishing status code.
    String freshLoginCsrfToken = fetchLoginCsrfToken(organizationId);
    HttpResponse<String> loginAfterSuspension =
        submitLoginWithBody(organizationId, freshLoginCsrfToken, email, password);
    assertThat(loginAfterSuspension.statusCode()).isEqualTo(200);
    assertThat(loginAfterSuspension.body()).contains("Invalid email or password.");

    Integer auditRows =
        jdbcTemplate.queryForObject(
            "select count(*) from audit_events where action = 'account.suspended' and"
                + " actor_type = 'PLATFORM_CLIENT' and target_type = 'Account' and target_id = ?",
            Integer.class,
            accountId.toString());
    assertThat(auditRows).as("the suspension itself must be audited").isEqualTo(1);

    HttpResponse<String> reactivateResponse = reactivateAccount(platformToken, accountId);
    assertThat(reactivateResponse.statusCode()).isEqualTo(204);
    assertThat(
            jdbcTemplate.queryForObject(
                "select status from accounts where id = ?", String.class, accountId))
        .isEqualTo("ACTIVE");

    String reactivatedLoginCsrfToken = fetchLoginCsrfToken(organizationId);
    HttpResponse<Void> loginAfterReactivation =
        submitLogin(organizationId, reactivatedLoginCsrfToken, email, password);
    assertThat(loginAfterReactivation.statusCode())
        .as("login must work again after reactivation")
        .isEqualTo(302);
  }

  @Test
  void returns404WhenSuspendingAnUnknownAccount() throws Exception {
    String platformToken = requestPlatformAccessToken(FULL_SCOPE);

    HttpResponse<String> response = suspendAccount(platformToken, UUID.randomUUID());

    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  void returns404WhenReactivatingAnUnknownAccount() throws Exception {
    String platformToken = requestPlatformAccessToken(FULL_SCOPE);

    HttpResponse<String> response = reactivateAccount(platformToken, UUID.randomUUID());

    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  void rejectsSuspensionWithoutTheAccountsSuspendScope() throws Exception {
    String platformTokenWithoutSuspendScope =
        requestPlatformAccessToken("platform:organizations:write");
    UUID organizationId =
        createOrganization(platformTokenWithoutSuspendScope, "No Suspend Scope Co");
    UUID accountId =
        registerAccount(organizationId, "scope-check@example.com", "a-correct-password");

    HttpResponse<String> response = suspendAccount(platformTokenWithoutSuspendScope, accountId);

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(
            jdbcTemplate.queryForObject(
                "select status from accounts where id = ?", String.class, accountId))
        .as("a rejected request must never change anything")
        .isEqualTo("ACTIVE");
  }

  private Integer countOauth2AuthorizationRowsForPrincipal(UUID accountId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from oauth2_authorization where principal_name = ?",
        Integer.class,
        accountId.toString());
  }

  private HttpResponse<String> suspendAccount(String platformToken, UUID accountId)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/api/v1/admin/accounts/" + accountId + ":suspend"))
            .header("Authorization", "Bearer " + platformToken)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> reactivateAccount(String platformToken, UUID accountId)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/api/v1/admin/accounts/" + accountId + ":reactivate"))
            .header("Authorization", "Bearer " + platformToken)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private String requestPlatformAccessToken(String scope) throws IOException, InterruptedException {
    String basicAuth =
        Base64.getEncoder()
            .encodeToString("test-platform-client:a-test-platform-secret".getBytes());
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/oauth2/token"))
            .header("Authorization", "Basic " + basicAuth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&scope=" + scope))
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
          "allowedGrantTypes": ["authorization_code"],
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

  private UUID registerAccount(UUID organizationId, String email, String password)
      throws IOException, InterruptedException {
    HttpRequest getForm =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/register")).GET().build();
    HttpResponse<String> formResponse =
        httpClient.send(getForm, HttpResponse.BodyHandlers.ofString());
    String csrfToken = extractCsrfToken(formResponse.body());

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

  private void getAuthorize(UUID organizationId, String clientId, String codeChallenge)
      throws IOException, InterruptedException {
    String query =
        "response_type=code"
            + "&client_id="
            + clientId
            + "&redirect_uri="
            + urlEncode(REDIRECT_URI)
            + "&scope=openid"
            + "&code_challenge="
            + codeChallenge
            + "&code_challenge_method=S256"
            + "&state=state-value";
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/oauth2/authorize?" + query))
            .GET()
            .build();
    httpClient.send(request, HttpResponse.BodyHandlers.discarding());
  }

  private String fetchLoginCsrfToken(UUID organizationId) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/login")).GET().build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    return extractCsrfToken(response.body());
  }

  private HttpResponse<Void> submitLogin(
      UUID organizationId, String csrfToken, String email, String password)
      throws IOException, InterruptedException {
    String body = "_csrf=" + csrfToken + "&email=" + email + "&password=" + password;
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/login"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.discarding());
  }

  private HttpResponse<String> submitLoginWithBody(
      UUID organizationId, String csrfToken, String email, String password)
      throws IOException, InterruptedException {
    String body = "_csrf=" + csrfToken + "&email=" + email + "&password=" + password;
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/login"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> exchangeCode(
      UUID organizationId, ClientCredentials client, String code, String codeVerifier)
      throws IOException, InterruptedException {
    String basicAuth =
        Base64.getEncoder()
            .encodeToString((client.clientId() + ":" + client.clientSecret()).getBytes());
    String body =
        "grant_type=authorization_code"
            + "&code="
            + code
            + "&redirect_uri="
            + urlEncode(REDIRECT_URI)
            + "&code_verifier="
            + codeVerifier;
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/oauth2/token"))
            .header("Authorization", "Basic " + basicAuth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<Void> getDiscardingBodyAbsolute(String absoluteUrl)
      throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(URI.create(absoluteUrl)).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.discarding());
  }

  private String generateCodeVerifier() {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String deriveCodeChallenge(String codeVerifier) throws NoSuchAlgorithmException {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
    return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
  }

  private static String extractCsrfToken(String html) {
    Matcher matcher = CSRF_TOKEN_PATTERN.matcher(html);
    assertThat(matcher.find()).as("page must render a _csrf hidden input").isTrue();
    return matcher.group(1);
  }

  private static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String queryParam(String url, String name) {
    String query = URI.create(url).getRawQuery();
    for (String pair : query.split("&")) {
      int equalsIndex = pair.indexOf('=');
      if (equalsIndex >= 0 && pair.substring(0, equalsIndex).equals(name)) {
        return java.net.URLDecoder.decode(pair.substring(equalsIndex + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  private URI baseUri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private record ClientCredentials(String clientId, String clientSecret) {}
}
