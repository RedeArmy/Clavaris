package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.app.support.TestMailSenderConfig;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Workspace-role login signal follow-up: proves {@code workspace_id}/{@code workspace_role} land on
 * a real ID token AND {@code /userinfo} for a real workspace-member login, and are absent for a
 * regular (non-member) account — same "confirmed live, not assumed" bar as {@code
 * WorkspaceIntegrationTest}/{@code DeleteAccountIntegrationTest}. The member's password is unknown
 * to this test (a random one {@code AddWorkspaceMemberService}'s own provisioning flow generates
 * and never surfaces) — this test completes the exact same password-reset-confirm flow a real
 * member would (mirroring {@code EmailVerificationAndPasswordResetIntegrationTest}'s own pattern)
 * before attempting login.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret"
    })
class WorkspaceRoleClaimIntegrationTest extends RedisBackedIntegrationTest {

  private static final String REDIRECT_URI = "https://client.example.test/callback";
  private static final Pattern CSRF_TOKEN_PATTERN =
      Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

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
  private final SecureRandom secureRandom = new SecureRandom();

  @Test
  void aWorkspaceMembersIdTokenAndUserinfoCarryTheWorkspaceRoleClaim() throws Exception {
    String platformToken =
        requestPlatformAccessToken(
            "platform:organizations:write platform:workspaces:write"
                + " platform:workspace-members:write");
    UUID organizationId = createOrganization(platformToken, "Claim Test Co");
    ClientCredentials client = registerOAuthClient(platformToken, organizationId);
    UUID workspaceId = createWorkspace(platformToken, organizationId, "Engineering");
    String email = "workspace-admin@example.com";
    addMember(platformToken, workspaceId, email, "ADMIN");
    String password = completePasswordSetup(organizationId, email);

    HttpResponse<String> tokenResponse =
        performFullLoginAndExchangeCode(organizationId, client, email, password);
    assertThat(tokenResponse.statusCode()).isEqualTo(200);
    JsonNode tokenBody = objectMapper.readTree(tokenResponse.body());
    String accessToken = tokenBody.get("access_token").asString();
    String idToken = tokenBody.get("id_token").asString();

    JsonNode idTokenClaims = decodeJwtClaims(idToken);
    assertThat(idTokenClaims.get("workspace_id").asString()).isEqualTo(workspaceId.toString());
    assertThat(idTokenClaims.get("workspace_role").asString()).isEqualTo("ADMIN");

    HttpResponse<String> userInfoResponse = getUserInfo(organizationId, accessToken);
    assertThat(userInfoResponse.statusCode()).isEqualTo(200);
    JsonNode userInfo = objectMapper.readTree(userInfoResponse.body());
    assertThat(userInfo.get("workspace_id").asString()).isEqualTo(workspaceId.toString());
    assertThat(userInfo.get("workspace_role").asString()).isEqualTo("ADMIN");
  }

  // Regression coverage for the real bug WorkspaceRoleClaimsCustomizerTest's own unit test
  // catches in isolation: RefreshTokenRotationAuthenticationProvider reports REFRESH_TOKEN as its
  // own grant type, not AUTHORIZATION_CODE — proves the claim survives a real silent refresh, end
  // to end, not just that the customizer's own guard accepts the right enum value.
  @Test
  void aWorkspaceMembersRefreshedTokenAlsoCarriesTheWorkspaceRoleClaim() throws Exception {
    String platformToken =
        requestPlatformAccessToken(
            "platform:organizations:write platform:workspaces:write"
                + " platform:workspace-members:write");
    UUID organizationId = createOrganization(platformToken, "Refresh Claim Co");
    ClientCredentials client = registerOAuthClientWithRefreshGrant(platformToken, organizationId);
    UUID workspaceId = createWorkspace(platformToken, organizationId, "Engineering");
    String email = "refresh-admin@example.com";
    addMember(platformToken, workspaceId, email, "ADMIN");
    String password = completePasswordSetup(organizationId, email);

    HttpResponse<String> tokenResponse =
        performFullLoginAndExchangeCode(organizationId, client, email, password);
    assertThat(tokenResponse.statusCode()).isEqualTo(200);
    String refreshToken =
        objectMapper.readTree(tokenResponse.body()).get("refresh_token").asString();

    HttpResponse<String> refreshResponse = refresh(organizationId, client, refreshToken);

    assertThat(refreshResponse.statusCode()).isEqualTo(200);
    JsonNode refreshedIdTokenClaims =
        decodeJwtClaims(objectMapper.readTree(refreshResponse.body()).get("id_token").asString());
    assertThat(refreshedIdTokenClaims.get("workspace_id").asString())
        .isEqualTo(workspaceId.toString());
    assertThat(refreshedIdTokenClaims.get("workspace_role").asString()).isEqualTo("ADMIN");
  }

  @Test
  void aRegularAccountsIdTokenCarriesNoWorkspaceClaimAtAll() throws Exception {
    String platformToken = requestPlatformAccessToken("platform:organizations:write");
    UUID organizationId = createOrganization(platformToken, "No Workspace Co");
    ClientCredentials client = registerOAuthClient(platformToken, organizationId);
    String email = "regular-user@example.com";
    String password = "a-correct-password";
    registerAccountDirectly(organizationId, email, password);

    HttpResponse<String> tokenResponse =
        performFullLoginAndExchangeCode(organizationId, client, email, password);
    assertThat(tokenResponse.statusCode()).isEqualTo(200);
    JsonNode idTokenClaims =
        decodeJwtClaims(objectMapper.readTree(tokenResponse.body()).get("id_token").asString());

    assertThat(idTokenClaims.has("workspace_id")).isFalse();
    assertThat(idTokenClaims.has("workspace_role")).isFalse();
  }

  private String completePasswordSetup(UUID organizationId, String email)
      throws IOException, InterruptedException {
    ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
    verify(mailSender)
        .sendPasswordReset(
            eq(email), eq(new OrganizationId(organizationId)), tokenCaptor.capture());
    String resetToken = tokenCaptor.getValue();
    String newPassword = "a-chosen-Str0ng-password!";

    HttpRequest getForm =
        HttpRequest.newBuilder(
                baseUri("/o/" + organizationId + "/reset-password?token=" + urlEncode(resetToken)))
            .GET()
            .build();
    HttpResponse<String> formResponse =
        httpClient.send(getForm, HttpResponse.BodyHandlers.ofString());
    String csrfToken = extractCsrfToken(formResponse.body());

    String body =
        "_csrf="
            + csrfToken
            + "&token="
            + urlEncode(resetToken)
            + "&newPassword="
            + urlEncode(newPassword)
            + "&confirmPassword="
            + urlEncode(newPassword);
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/reset-password"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
    assertThat(response.statusCode()).isEqualTo(302);
    return newPassword;
  }

  private void registerAccountDirectly(UUID organizationId, String email, String password)
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
  }

  private HttpResponse<String> performFullLoginAndExchangeCode(
      UUID organizationId, ClientCredentials client, String email, String password)
      throws Exception {
    String codeVerifier = generateCodeVerifier();
    String codeChallenge = deriveCodeChallenge(codeVerifier);
    getAuthorize(organizationId, client.clientId(), codeChallenge);
    String loginCsrfToken = fetchLoginCsrfToken(organizationId);
    HttpResponse<Void> loginResponse = submitLogin(organizationId, loginCsrfToken, email, password);
    String backToAuthorize = loginResponse.headers().firstValue("Location").orElseThrow();
    HttpResponse<Void> authorizedResponse = getDiscardingBodyAbsolute(backToAuthorize);
    String redirectWithCode = authorizedResponse.headers().firstValue("Location").orElseThrow();
    String code = queryParam(redirectWithCode, "code");
    return exchangeCode(organizationId, client, code, codeVerifier);
  }

  private JsonNode decodeJwtClaims(String jwt) {
    String[] parts = jwt.split("\\.");
    byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
    return objectMapper.readTree(payload);
  }

  private HttpResponse<String> getUserInfo(UUID organizationId, String accessToken)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/userinfo"))
            .header("Authorization", "Bearer " + accessToken)
            .GET()
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> addMember(
      String platformToken, UUID workspaceId, String email, String role)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/api/v1/admin/workspaces/" + workspaceId + "/members"))
            .header("Authorization", "Bearer " + platformToken)
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}"))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(201);
    return response;
  }

  private UUID createWorkspace(String platformToken, UUID organizationId, String name)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(
                baseUri("/api/v1/admin/organizations/" + organizationId + "/workspaces"))
            .header("Authorization", "Bearer " + platformToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"" + name + "\"}"))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(201);
    return UUID.fromString(objectMapper.readTree(response.body()).get("id").asString());
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

  private ClientCredentials registerOAuthClientWithRefreshGrant(
      String platformToken, UUID organizationId) throws IOException, InterruptedException {
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

  private HttpResponse<String> refresh(
      UUID organizationId, ClientCredentials client, String refreshToken)
      throws IOException, InterruptedException {
    String basicAuth =
        Base64.getEncoder()
            .encodeToString((client.clientId() + ":" + client.clientSecret()).getBytes());
    String body = "grant_type=refresh_token&refresh_token=" + urlEncode(refreshToken);
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/oauth2/token"))
            .header("Authorization", "Basic " + basicAuth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
    String body = "_csrf=" + csrfToken + "&email=" + email + "&password=" + urlEncode(password);
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
