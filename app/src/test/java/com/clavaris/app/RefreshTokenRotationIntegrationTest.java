package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLDecoder;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * BR-ID-03, end to end against real code, real Postgres — test-strategy.md §3's own words: "the
 * single highest-value security invariant in the system." Same "confirmed live, not assumed" bar
 * every other issuance test in this suite already holds itself to.
 *
 * <p>Not a subclass/shared-base of {@link AuthorizationCodeFlowIntegrationTest} — this codebase's
 * own convention (confirmed across every other integration test in this package) is self-contained
 * test files, each with its own copy of the small set of raw-HTTP helpers, not a shared base class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret"
    })
class RefreshTokenRotationIntegrationTest {

  private static final String REDIRECT_URI = "https://client.example.test/callback";
  private static final Pattern CSRF_TOKEN_PATTERN =
      Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

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
  void rotatesOnUseAndDetectsReuseByRevokingEveryActiveTokenForTheAccount() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Refresh Token Co");
    ClientCredentials client = registerOAuthClient(platformToken, organizationId);
    registerAccount(organizationId, "refresh-user@example.com", "a-correct-password");

    JsonNode initialTokens =
        firstTokenResponse(
            organizationId, client, "refresh-user@example.com", "a-correct-password");
    String accountId = extractAccountIdFromAnAccessToken(initialTokens);
    String initialRefreshToken = initialTokens.get("refresh_token").asString();
    String initialAccessToken = initialTokens.get("access_token").asString();
    assertThat(initialRefreshToken)
        .as("allowedGrantTypes includes refresh_token, so the initial exchange must issue one")
        .isNotBlank();

    // 1. A real refresh: rotates into a genuinely new access token and a genuinely new refresh
    // token, never the same values back.
    HttpResponse<String> firstRefresh = refresh(organizationId, client, initialRefreshToken);
    assertThat(firstRefresh.statusCode()).isEqualTo(200);
    JsonNode firstRefreshBody = objectMapper.readTree(firstRefresh.body());
    String rotatedAccessToken = firstRefreshBody.get("access_token").asString();
    String rotatedRefreshToken = firstRefreshBody.get("refresh_token").asString();
    assertThat(rotatedAccessToken).isNotBlank().isNotEqualTo(initialAccessToken);
    assertThat(rotatedRefreshToken).isNotBlank().isNotEqualTo(initialRefreshToken);

    // 2. Reusing the ORIGINAL (now-rotated-away) refresh token must fail — the actual BR-ID-03
    // invariant, not just "the old value happens to not match anymore".
    HttpResponse<String> reuseAttempt = refresh(organizationId, client, initialRefreshToken);
    assertThat(reuseAttempt.statusCode()).isEqualTo(400);
    assertThat(reuseAttempt.body()).contains("invalid_grant");

    // 3. The reuse cascade must have revoked EVERY active token for the account, not just the
    // reused one — the refresh token issued in step 1 (never itself presented again) must also be
    // dead now.
    HttpResponse<String> rotatedTokenNowRejected =
        refresh(organizationId, client, rotatedRefreshToken);
    assertThat(rotatedTokenNowRejected.statusCode())
        .as("BR-ID-03: reuse revokes every active token for the account, not just the reused one")
        .isEqualTo(400);

    // 4. Direct DB proof, not just an HTTP-level inference: every oauth2_authorization row for
    // this account's principal must be gone — TD-SEC-003's AccountTokenRevokerBridge actually ran.
    Integer remainingAuthorizations =
        jdbcTemplate.queryForObject(
            "select count(*) from oauth2_authorization where principal_name = ?",
            Integer.class,
            accountId);
    assertThat(remainingAuthorizations)
        .as(
            "every access/ID token this account ever had must be revoked, including the one"
                + " minted by the first (legitimate) refresh in step 1")
        .isZero();
  }

  @Test
  void rejectsARefreshRequestWithScopesBeyondWhatWasOriginallyAuthorized() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Refresh Scope Co");
    ClientCredentials client = registerOAuthClient(platformToken, organizationId);
    registerAccount(organizationId, "scope-user@example.com", "a-correct-password");

    JsonNode tokens =
        firstTokenResponse(organizationId, client, "scope-user@example.com", "a-correct-password");
    String refreshToken = tokens.get("refresh_token").asString();

    // RFC 6749 §6: a refresh request may never escalate scope beyond what the original
    // authorization granted — "openid profile" was never requested/granted, only "openid".
    HttpResponse<String> response =
        refreshWithScope(organizationId, client, refreshToken, "openid profile");

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("invalid_scope");
  }

  private JsonNode firstTokenResponse(
      UUID organizationId, ClientCredentials client, String email, String password)
      throws IOException, InterruptedException {
    String codeVerifier = generateCodeVerifier();
    String codeChallenge = deriveCodeChallenge(codeVerifier);

    getAuthorize(organizationId, client.clientId(), codeChallenge, "state-value");
    String loginCsrfToken = fetchLoginCsrfToken(organizationId);
    HttpResponse<Void> loginResponse = submitLogin(organizationId, loginCsrfToken, email, password);
    String backToAuthorize = loginResponse.headers().firstValue("Location").orElseThrow();
    HttpResponse<Void> authorizedResponse = getAbsoluteDiscardingBody(backToAuthorize);
    String redirectWithCode = authorizedResponse.headers().firstValue("Location").orElseThrow();
    String code = queryParam(redirectWithCode, "code");

    HttpResponse<String> tokenResponse = exchangeCode(organizationId, client, code, codeVerifier);
    assertThat(tokenResponse.statusCode()).isEqualTo(200);
    return objectMapper.readTree(tokenResponse.body());
  }

  // Decodes the unsigned middle segment of the JWT access token to read its own `sub` claim —
  // exactly what SpringSecurityAuthenticatedSessionEstablisher set as the principal at login
  // (accountId.toString()), which is what oauth2_authorization.principal_name is keyed on.
  private String extractAccountIdFromAnAccessToken(JsonNode tokenResponse) {
    String accessToken = tokenResponse.get("access_token").asString();
    String[] parts = accessToken.split("\\.");
    byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
    JsonNode claims = objectMapper.readTree(new String(payloadBytes, StandardCharsets.UTF_8));
    return claims.get("sub").asString();
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
                    "grant_type=client_credentials&scope=platform:organizations:write"))
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
            .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"" + name + "\"}"))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    return UUID.fromString(objectMapper.readTree(response.body()).get("id").asString());
  }

  private ClientCredentials registerOAuthClient(String platformToken, UUID organizationId)
      throws IOException, InterruptedException {
    String requestBody =
        """
        {
          "redirectUris": ["%s"],
          "allowedGrantTypes": ["authorization_code", "refresh_token"],
          "allowedScopes": ["openid", "profile"]
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

  private void registerAccount(UUID organizationId, String email, String password)
      throws IOException, InterruptedException {
    HttpRequest getForm =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/register")).GET().build();
    HttpResponse<String> formResponse =
        httpClient.send(getForm, HttpResponse.BodyHandlers.ofString());
    Matcher matcher = CSRF_TOKEN_PATTERN.matcher(formResponse.body());
    assertThat(matcher.find()).isTrue();
    String csrfToken = matcher.group(1);

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
    HttpResponse<Void> response = httpClient.send(register, HttpResponse.BodyHandlers.discarding());
    assertThat(response.statusCode()).isEqualTo(302);
  }

  private void getAuthorize(
      UUID organizationId, String clientId, String codeChallenge, String state)
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
            + "&state="
            + state;
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
    Matcher matcher = CSRF_TOKEN_PATTERN.matcher(response.body());
    assertThat(matcher.find()).isTrue();
    return matcher.group(1);
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

  private HttpResponse<String> refresh(
      UUID organizationId, ClientCredentials client, String refreshToken)
      throws IOException, InterruptedException {
    return refreshWithScope(organizationId, client, refreshToken, null);
  }

  private HttpResponse<String> refreshWithScope(
      UUID organizationId, ClientCredentials client, String refreshToken, String scope)
      throws IOException, InterruptedException {
    String basicAuth =
        Base64.getEncoder()
            .encodeToString((client.clientId() + ":" + client.clientSecret()).getBytes());
    String body =
        "grant_type=refresh_token"
            + "&refresh_token="
            + urlEncode(refreshToken)
            + (scope != null ? "&scope=" + urlEncode(scope) : "");
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/oauth2/token"))
            .header("Authorization", "Basic " + basicAuth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<Void> getAbsoluteDiscardingBody(String absoluteUrl)
      throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(URI.create(absoluteUrl)).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.discarding());
  }

  private URI baseUri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private String generateCodeVerifier() {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String deriveCodeChallenge(String codeVerifier) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available on this JVM", e);
    }
  }

  private static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String queryParam(String url, String name) {
    String query = URI.create(url).getRawQuery();
    for (String pair : query.split("&")) {
      int equalsIndex = pair.indexOf('=');
      if (equalsIndex >= 0 && pair.substring(0, equalsIndex).equals(name)) {
        return URLDecoder.decode(pair.substring(equalsIndex + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  private record ClientCredentials(String clientId, String clientSecret) {}
}
