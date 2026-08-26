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
 * BR-DATA-02/03's own organization-level equivalent: {@code POST
 * /api/v1/admin/organizations/{organizationId}:delete} — proves the whole erasure chain ({@link
 * com.clavaris.app.infrastructure.config.OrganizationTokenRevokerBridge}, {@link
 * com.clavaris.app.infrastructure.config.OrganizationIdentityDataEraserBridge}, {@link
 * com.clavaris.app.infrastructure.config.OrganizationOAuthClientsEraserBridge}) with real rows in
 * every affected table, not mocked ports — same "confirmed live, not assumed" bar as {@code
 * DeleteAccountIntegrationTest}. Deliberately builds one row in every table this Organization owns
 * (an Account with a real Authorization Code exchange, an OAuthClient, a SigningKey — auto-
 * provisioned at creation, a RateLimitPolicy) so the after-state assertions prove something real
 * was actually removed, not just that empty tables stayed empty.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret"
    })
class DeleteOrganizationIntegrationTest extends RedisBackedIntegrationTest {

  private static final String REDIRECT_URI = "https://client.example.test/callback";
  private static final Pattern CSRF_TOKEN_PATTERN =
      Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

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
  void deletingAnOrganizationRemovesEveryRowItOwnsAcrossEveryModule() throws Exception {
    String platformToken =
        requestPlatformAccessToken(
            "platform:organizations:write platform:organizations:delete"
                + " platform:rate-limit-policy:write");
    UUID organizationId = createOrganization(platformToken, "Org Deletion Co");
    ClientCredentials client = registerOAuthClient(platformToken, organizationId);
    String email = "org-deletion@example.com";
    UUID accountId = registerAccount(organizationId, email, "a-correct-password");
    setRateLimitPolicy(platformToken, organizationId, 500);

    String codeVerifier = generateCodeVerifier();
    String codeChallenge = deriveCodeChallenge(codeVerifier);
    getAuthorize(organizationId, client.clientId(), codeChallenge);
    String loginCsrfToken = fetchLoginCsrfToken(organizationId);
    HttpResponse<Void> loginResponse =
        submitLogin(organizationId, loginCsrfToken, email, "a-correct-password");
    String backToAuthorize = loginResponse.headers().firstValue("Location").orElseThrow();
    HttpResponse<Void> authorizedResponse = getDiscardingBodyAbsolute(backToAuthorize);
    String redirectWithCode = authorizedResponse.headers().firstValue("Location").orElseThrow();
    String code = queryParam(redirectWithCode, "code");
    HttpResponse<String> tokenResponse = exchangeCode(organizationId, client, code, codeVerifier);
    assertThat(tokenResponse.statusCode()).isEqualTo(200);

    // Pre-delete sanity: every real row this test is about to prove gets erased must genuinely
    // exist first — an empty-before-and-after test would prove nothing.
    assertThat(countOrganizations(organizationId)).isEqualTo(1);
    assertThat(countAccounts(organizationId)).isEqualTo(1);
    assertThat(countPasswordCredentials(accountId)).isEqualTo(1);
    assertThat(countOAuthClients(organizationId)).isEqualTo(1);
    assertThat(countSigningKeys(organizationId))
        .as("CreateOrganizationService auto-provisions one active key")
        .isGreaterThanOrEqualTo(1);
    assertThat(countRateLimitPolicies(organizationId)).isEqualTo(1);
    assertThat(countOauth2AuthorizationRows(accountId, client.clientId()))
        .as("the real Authorization Code exchange above must have left a token behind")
        .isGreaterThan(0);

    HttpResponse<String> deleteResponse = deleteOrganization(platformToken, organizationId);
    assertThat(deleteResponse.statusCode()).isEqualTo(204);

    assertThat(countOrganizations(organizationId)).as("the Organization row itself").isZero();
    assertThat(countAccounts(organizationId)).as("every Account it owned").isZero();
    assertThat(countPasswordCredentials(accountId))
        .as("password_credentials must cascade-delete with its Account")
        .isZero();
    assertThat(countOAuthClients(organizationId)).as("every OAuthClient it registered").isZero();
    assertThat(countSigningKeys(organizationId)).as("every SigningKey it ever rotated").isZero();
    assertThat(countRateLimitPolicies(organizationId))
        .as("its own RateLimitPolicy (same-module DB cascade)")
        .isZero();
    assertThat(countOauth2AuthorizationRows(accountId, client.clientId()))
        .as("every SAS-managed token this Organization's Account/OAuthClient ever obtained")
        .isZero();

    Integer auditRows =
        jdbcTemplate.queryForObject(
            "select count(*) from audit_events where action = 'organization.deleted' and"
                + " actor_type = 'PLATFORM_CLIENT' and target_type = 'Organization' and"
                + " target_id = ?",
            Integer.class,
            organizationId.toString());
    assertThat(auditRows).as("the deletion itself must be audited").isEqualTo(1);
  }

  @Test
  void returns404WhenTheOrganizationDoesNotExist() throws Exception {
    String platformToken =
        requestPlatformAccessToken("platform:organizations:write platform:organizations:delete");

    HttpResponse<String> response = deleteOrganization(platformToken, UUID.randomUUID());

    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  void rejectsDeletionWithoutTheOrganizationsDeleteScope() throws Exception {
    // requestPlatformAccessToken below only asks for organizations:write — same defence-in-depth
    // proof this suite's siblings (DeleteAccountIntegrationTest et al.) already apply to their own
    // scopes.
    String platformTokenWithoutDeleteScope =
        requestPlatformAccessToken("platform:organizations:write");
    UUID organizationId =
        createOrganization(platformTokenWithoutDeleteScope, "No Delete Scope Org Co");

    HttpResponse<String> response =
        deleteOrganization(platformTokenWithoutDeleteScope, organizationId);

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(countOrganizations(organizationId))
        .as("a rejected request must never delete anything")
        .isEqualTo(1);
  }

  private Integer countOrganizations(UUID organizationId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from organizations where id = ?", Integer.class, organizationId);
  }

  private Integer countAccounts(UUID organizationId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from accounts where organization_id = ?", Integer.class, organizationId);
  }

  private Integer countPasswordCredentials(UUID accountId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from password_credentials where account_id = ?", Integer.class, accountId);
  }

  private Integer countOAuthClients(UUID organizationId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from oauth_clients where organization_id = ?",
        Integer.class,
        organizationId);
  }

  private Integer countSigningKeys(UUID organizationId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from signing_keys where organization_id = ?",
        Integer.class,
        organizationId);
  }

  private Integer countRateLimitPolicies(UUID organizationId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from rate_limit_policies where organization_id = ?",
        Integer.class,
        organizationId);
  }

  private Integer countOauth2AuthorizationRows(UUID accountId, String clientId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from oauth2_authorization where principal_name = ? or"
            + " registered_client_id = (select id::text from oauth_clients where client_id = ?)",
        Integer.class,
        accountId.toString(),
        clientId);
  }

  private HttpResponse<String> deleteOrganization(String platformToken, UUID organizationId)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/api/v1/admin/organizations/" + organizationId + ":delete"))
            .header("Authorization", "Bearer " + platformToken)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> setRateLimitPolicy(
      String platformToken, UUID organizationId, int requestsPerMinute)
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
