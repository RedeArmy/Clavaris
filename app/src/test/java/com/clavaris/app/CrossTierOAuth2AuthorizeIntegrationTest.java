package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.clavaris.app.support.TestMailSenderConfig;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.PlatformMailSender;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Security finding (SDE-III review, 2026-08-22), regression test for its fix — the mirror-image of
 * {@code PlatformAccountDashboardIntegrationTest}'s own cross-tier regression test: a {@code
 * PlatformAccount} session, established entirely via {@code /platform/login} and never touching any
 * {@code Organization}'s own login page, must not be usable as the resource owner completing an
 * {@code /o/{organizationId}/oauth2/authorize} request. Before {@code
 * TenantAccountOnlySecurityContextFilter}'s fix, Spring Authorization Server's own {@code
 * OAuth2AuthorizationCodeRequestAuthenticationConverter} would have accepted that session's
 * authenticated {@code SecurityContext} as-is (confirmed by decompilation — see that filter's own
 * Javadoc) and minted a real authorization code whose principal was the {@code PlatformAccountId},
 * for an Organization the account never registered with.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=cross-tier-test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-cross-tier-test-platform-secret"
    })
class CrossTierOAuth2AuthorizeIntegrationTest {

  private static final String REDIRECT_URI = "https://client.example.test/callback";
  private static final Pattern CSRF_TOKEN_PATTERN =
      Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Autowired private PlatformMailSender mailSender;
  @Autowired private PlatformAccountRepository platformAccounts;

  private final HttpClient httpClient =
      HttpClient.newBuilder()
          .cookieHandler(new CookieManager())
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final SecureRandom secureRandom = new SecureRandom();

  @Test
  void aPlatformAccountSessionCannotCompleteATenantOrganizationsAuthorizeRequest()
      throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Cross Tier Co");
    ClientCredentials client = registerOAuthClient(platformToken, organizationId);

    // Establishes a real, fully authenticated PlatformAccount session — /platform/register,
    // /platform/verify-email, /platform/login — on the same cookie-jar httpClient used below, the
    // same way a browser would carry it.
    loginAsAPlatformAccount("founder@example.com", "the-platform-password");

    String codeChallenge = deriveCodeChallenge(generateCodeVerifier());
    HttpResponse<Void> authorizeResponse =
        getAuthorize(organizationId, client.clientId(), codeChallenge, "opaque-state");

    assertThat(authorizeResponse.statusCode()).isEqualTo(302);
    String location = authorizeResponse.headers().firstValue("Location").orElseThrow();
    assertThat(location)
        .as(
            "a PlatformAccount session must be treated as anonymous on this Organization's own "
                + "/oauth2/authorize, sent to its login page — not accepted as the resource owner")
        .endsWith("/o/" + organizationId + "/login")
        .doesNotContain("code=")
        .doesNotStartWith(REDIRECT_URI);
  }

  private void loginAsAPlatformAccount(String email, String password)
      throws IOException, InterruptedException {
    HttpResponse<String> registerForm =
        httpClient.send(
            HttpRequest.newBuilder(baseUri("/platform/register")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    String registerCsrfToken = extractCsrfToken(registerForm.body());
    String registerBody =
        "_csrf="
            + registerCsrfToken
            + "&email="
            + email
            + "&password="
            + password
            + "&confirmPassword="
            + password;
    httpClient.send(
        HttpRequest.newBuilder(baseUri("/platform/register"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(registerBody))
            .build(),
        HttpResponse.BodyHandlers.discarding());

    ArgumentCaptor<String> verificationToken = ArgumentCaptor.forClass(String.class);
    verify(mailSender).sendPlatformAccountEmailVerification(eq(email), verificationToken.capture());
    httpClient.send(
        HttpRequest.newBuilder(
                baseUri("/platform/verify-email?token=" + urlEncode(verificationToken.getValue())))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.discarding());

    HttpResponse<String> loginForm =
        httpClient.send(
            HttpRequest.newBuilder(baseUri("/platform/login")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    String loginCsrfToken = extractCsrfToken(loginForm.body());
    String loginBody =
        "_csrf=" + loginCsrfToken + "&email=" + email + "&password=" + urlEncode(password);
    HttpResponse<Void> loginResponse =
        httpClient.send(
            HttpRequest.newBuilder(baseUri("/platform/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(loginBody))
                .build(),
            HttpResponse.BodyHandlers.discarding());
    assertThat(loginResponse.statusCode())
        .as("the PlatformAccount session this whole test depends on must really be established")
        .isEqualTo(302);
  }

  private String requestPlatformAccessToken() throws IOException, InterruptedException {
    String basicAuth =
        Base64.getEncoder()
            .encodeToString(
                "cross-tier-test-platform-client:a-cross-tier-test-platform-secret".getBytes());
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

  // A real PlatformAccount row, written directly through the repository rather than the full
  // /platform/register + /platform/verify-email HTTP flow — CreateOrganizationService (security
  // finding, SDE-III review, 2026-08-22) now validates ownerPlatformAccountId against a real row.
  // Deliberately a different PlatformAccount than loginAsAPlatformAccount() establishes a session
  // for above — this Organization's real owner is irrelevant to what this test proves.
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

  private ClientCredentials registerOAuthClient(String platformToken, UUID organizationId)
      throws IOException, InterruptedException {
    String requestBody =
        """
        {
          "redirectUris": ["%s"],
          "allowedGrantTypes": ["authorization_code"],
          "allowedScopes": ["openid"]
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

  private HttpResponse<Void> getAuthorize(
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
    return httpClient.send(request, HttpResponse.BodyHandlers.discarding());
  }

  private static String extractCsrfToken(String html) {
    Matcher matcher = CSRF_TOKEN_PATTERN.matcher(html);
    assertThat(matcher.find()).as("page must render a _csrf hidden input").isTrue();
    return matcher.group(1);
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

  private URI baseUri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private record ClientCredentials(String clientId, String clientSecret) {}
}
