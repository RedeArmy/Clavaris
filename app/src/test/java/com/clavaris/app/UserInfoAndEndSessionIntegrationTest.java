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
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * TD-SEC-028: {@code /o/{organizationId}/userinfo} and the OIDC RP-Initiated Logout {@code
 * end_session_endpoint} were both listed "✅ Shipped" in {@code roadmap-and-release-plan.md} §2
 * (Authorization Code flow + PKCE, discovery/JWKS/userinfo/revoke/end-session) — neither had ever
 * been invoked by a single test, on the reasoning that Spring Authorization Server enables both
 * automatically via {@code .oidc(Customizer.withDefaults())}. Live-verifying that assumption found
 * both were real, previously-undiscovered dead ends:
 *
 * <ul>
 *   <li><b>{@code /userinfo} always returned 401</b>, even for a fully valid access token — SAS's
 *       own {@code OAuth2AuthorizationServerConfigurer.init()} does wire {@code
 *       .oauth2ResourceServer(jwt(Customizer.withDefaults()))} automatically once OIDC UserInfo is
 *       enabled (decompiled confirmation), but that default customizer needs a real {@code
 *       JwtDecoder} to actually authenticate a Bearer token against — none existed anywhere in this
 *       application, so the endpoint's own filter never had a populated {@code SecurityContext} to
 *       read a principal from. Fixed in {@code OrganizationAuthorizationServerConfig} by wiring a
 *       real {@code NimbusJwtDecoder.withJwkSource(jwksPublishingSource)} (the same tenant-scoped,
 *       overlap-window-aware source the JWKS endpoint itself already publishes from), plus {@link
 *       OrganizationJwtIssuerValidator} for explicit defense in depth on the {@code iss} claim.
 *   <li><b>{@code end_session_endpoint} 404'd unconditionally</b> — {@code
 *       OrganizationAuthorizationServerConfig}'s own {@code securityMatcher} never included SAS's
 *       default {@code oidcLogoutEndpoint} path ({@code /connect/logout}), so every request to it
 *       fell through to {@code DefaultSecurityConfig}'s permissive catch-all chain, which has zero
 *       SAS filters registered at all — the discovery document confidently advertised an {@code
 *       end_session_endpoint} that could never actually be reached. Fixed by adding {@code
 *       /o/*&#47;connect/logout} to the securityMatcher and {@code permitAll} (RP-Initiated Logout
 *       is explicitly designed to keep working once the browser's own session here has already
 *       expired — {@code OidcLogoutAuthenticationProvider} validates the presented {@code
 *       id_token_hint} itself, not ambient session state).
 * </ul>
 *
 * <p>Investigating {@code /userinfo} live also surfaced a second, independent, previously-dormant
 * bug: the first real attempt to deserialize a persisted {@code OAuth2Authorization}'s stored ID
 * token claims (something nothing had ever done, since nothing had ever successfully reached this
 * endpoint) failed — {@code AuthenticationContextClaimsCustomizer}'s own {@code amr} claim used
 * {@code List.of(...)} ({@code java.util.ImmutableCollections$List12} at runtime), a type {@code
 * JdbcOAuth2AuthorizationService}'s own Jackson3 {@code PolymorphicTypeValidator} rejects on
 * read-back. Fixed by using a plain {@code ArrayList} instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret"
    })
class UserInfoAndEndSessionIntegrationTest extends RedisBackedIntegrationTest {

  private static final String REDIRECT_URI = "https://client.example.test/callback";
  private static final Pattern CSRF_TOKEN_PATTERN =
      Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Autowired private PlatformAccountRepository platformAccounts;

  private final CookieManager cookieManager = new CookieManager();
  private final HttpClient httpClient =
      HttpClient.newBuilder()
          .cookieHandler(cookieManager)
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final SecureRandom secureRandom = new SecureRandom();

  @Test
  void userInfoReturnsTheRealSubjectClaimForAValidAccessToken() throws Exception {
    IssuedTokens tokens = completeARealAuthorizationCodeExchange("userinfo-happy@example.com");

    HttpResponse<String> response =
        getWithBearer(
            "/o/" + tokens.organizationId() + "/userinfo", tokens.tokenResponse().accessToken());

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = objectMapper.readTree(response.body());
    assertThat(body.get("sub").asString()).isEqualTo(tokens.subject());
  }

  @Test
  void userInfoRejectsARequestWithNoBearerTokenAtAll() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "UserInfo No Token Co");

    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/userinfo")).GET().build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void userInfoRejectsAWellFormedButUnknownBearerToken() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "UserInfo Garbage Token Co");

    HttpResponse<String> response =
        getWithBearer("/o/" + organizationId + "/userinfo", "not-a-real-jwt-at-all");

    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void endSessionRedirectsAfterARealIdTokenHint() throws Exception {
    IssuedTokens tokens = completeARealAuthorizationCodeExchange("logout-happy@example.com");

    HttpResponse<Void> response =
        getDiscardingBody(
            "/o/"
                + tokens.organizationId()
                + "/connect/logout?id_token_hint="
                + urlEncode(tokens.tokenResponse().idToken()));

    // SAS's own default: redirects to "/" (this application's own base URL) when no
    // post_logout_redirect_uri was presented — OrganizationRegisteredClientRepository never wires
    // RegisteredClient.postLogoutRedirectUri(...) today (TD-FUT-018, tracked, not this row's own
    // scope — post_logout_redirect_uri is genuinely optional per the RP-Initiated Logout spec, so
    // its absence doesn't stop this endpoint from being real and working).
    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(response.headers().firstValue("Location")).isPresent();
  }

  @Test
  void endSessionRejectsAMissingIdTokenHint() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Logout No Hint Co");

    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/connect/logout"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
  }

  @Test
  void endSessionRejectsAnIdTokenHintFromADifferentOrganizationWithoutLeakingA500()
      throws Exception {
    // ADR-0010 §5's own "structurally impossible, not policy-disallowed" tenant-isolation bar,
    // exercised at an endpoint this row is the very first coverage of:
    // OrganizationRegisteredClientRepository.findById is tenant-scoped and returns null for a
    // cross-org lookup, which OidcLogoutAuthenticationProvider turns into an Assert.notNull
    // failure — proving that lands as a clean 400 (OidcLogoutEndpointFilter's own broad
    // catch-all), never an unhandled 500 leaking a stack trace, matters here specifically because
    // nothing had ever exercised this catch clause before.
    IssuedTokens tokens = completeARealAuthorizationCodeExchange("logout-cross-tenant@example.com");
    String platformToken = requestPlatformAccessToken();
    UUID otherOrganizationId = createOrganization(platformToken, "Other Org For Logout Co");

    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(
                    baseUri(
                        "/o/"
                            + otherOrganizationId
                            + "/connect/logout?id_token_hint="
                            + urlEncode(tokens.tokenResponse().idToken())))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
  }

  // Full real login → authorize → token exchange, same shape as
  // AuthorizationCodeFlowIntegrationTest's own flagship test — requireConsent:false, since this
  // suite is about userinfo/logout, not consent (which has its own dedicated coverage).
  private IssuedTokens completeARealAuthorizationCodeExchange(String email)
      throws IOException, InterruptedException, NoSuchAlgorithmException {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "UserInfo/Logout Co " + email);
    ClientCredentials client = registerOAuthClient(platformToken, organizationId);
    registerAccount(organizationId, email, "a-correct-password");

    String codeVerifier = generateCodeVerifier();
    String codeChallenge = deriveCodeChallenge(codeVerifier);

    getAuthorize(organizationId, client.clientId(), codeChallenge, "state-value");
    String loginCsrfToken = fetchLoginCsrfToken(organizationId);
    HttpResponse<Void> loginResponse =
        submitLogin(organizationId, loginCsrfToken, email, "a-correct-password");
    String backToAuthorize = loginResponse.headers().firstValue("Location").orElseThrow();
    HttpResponse<Void> authorizedResponse = getDiscardingBodyAbsolute(backToAuthorize);
    String redirectWithCode = authorizedResponse.headers().firstValue("Location").orElseThrow();
    String code = queryParam(redirectWithCode, "code");

    HttpResponse<String> tokenResponse = exchangeCode(organizationId, client, code, codeVerifier);
    assertThat(tokenResponse.statusCode())
        .as("test setup itself must succeed before either endpoint under test can be exercised")
        .isEqualTo(200);
    JsonNode tokenBody = objectMapper.readTree(tokenResponse.body());
    String accessToken = tokenBody.get("access_token").asString();
    String idToken = tokenBody.get("id_token").asString();
    String subject =
        objectMapper
            .readTree(new String(base64UrlDecode(idToken.split("\\.")[1])))
            .get("sub")
            .asString();

    return new IssuedTokens(organizationId, new TokenResponse(accessToken, idToken), subject);
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

  private HttpResponse<String> getWithBearer(String path, String bearerToken)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUri(path))
            .header("Authorization", "Bearer " + bearerToken)
            .GET()
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<Void> getDiscardingBody(String path)
      throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(baseUri(path)).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.discarding());
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

  private static byte[] base64UrlDecode(String value) {
    return Base64.getUrlDecoder().decode(value);
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

  private record TokenResponse(String accessToken, String idToken) {}

  private record IssuedTokens(UUID organizationId, TokenResponse tokenResponse, String subject) {}
}
