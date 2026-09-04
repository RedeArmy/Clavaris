package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.app.support.TestMailSenderConfig;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.Map;
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
 * SDE-III feature build, 2026-09-03 — Impersonation: proves the minted access token is real and
 * verifiable (cryptographically verifies against the target Organization's own published JWKS,
 * carries the RFC 8693 {@code act} claim), revocable ({@code /oauth2/revoke}), and every guard rail
 * actually rejects what it claims to (cross-tenant client, over-broad scope, inactive Account,
 * missing scope on the calling platform token) — same "confirmed live, not assumed" bar every other
 * issuance test in this suite already holds itself to.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret"
    })
class ImpersonateAccountIntegrationTest extends RedisBackedIntegrationTest {

  private static final String REDIRECT_URI = "https://client.example.test/callback";
  private static final Pattern CSRF_TOKEN_PATTERN =
      Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");
  private static final String IMPERSONATE_SCOPE =
      "platform:organizations:write platform:accounts:impersonate";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Autowired private PlatformAccountRepository platformAccounts;
  @Autowired private JdbcTemplate jdbcTemplate;

  // registerAccount below is a cookie/session-backed, CSRF-protected form flow (GET renders the
  // form + session cookie + CSRF token, POST must carry the same session back) — a plain
  // HttpClient.newHttpClient() (no cookie handler) silently loses that session between the two
  // calls, so the POST 403s on CSRF mismatch and the account is never actually created. Same fix
  // AccountSuspensionIntegrationTest's own identical registerAccount helper already applies.
  private final CookieManager cookieManager = new CookieManager();
  private final HttpClient httpClient =
      HttpClient.newBuilder()
          .cookieHandler(cookieManager)
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void mintsAVerifiableTokenCarryingTheActClaimAndAnOauth2AuthorizationRow() throws Exception {
    String platformToken = requestPlatformAccessToken(IMPERSONATE_SCOPE);
    UUID organizationId = createOrganization(platformToken, "Impersonation Co");
    ClientCredentials client = registerOAuthClient(platformToken, organizationId, "openid profile");
    UUID accountId =
        registerAccount(organizationId, "impersonate-me@example.com", "a-correct-password");

    HttpResponse<String> response =
        impersonate(platformToken, accountId, client.clientId(), "[\"openid\"]");

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = objectMapper.readTree(response.body());
    assertThat(body.get("tokenType").asString()).isEqualTo("Bearer");
    assertThat(body.get("scope").toString()).contains("openid");
    assertThat(body.has("refreshToken")).isFalse();
    assertThat(body.has("idToken")).isFalse();
    String accessToken = body.get("accessToken").asString();

    SignedJWT jwt = SignedJWT.parse(accessToken);
    assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(accountId.toString());
    assertThat(jwt.getJWTClaimsSet().getIssuer())
        .isEqualTo("http://localhost:" + port + "/o/" + organizationId);
    assertThat(jwt.getJWTClaimsSet().getStringListClaim("amr")).containsExactly("imp");
    @SuppressWarnings("unchecked")
    Map<String, Object> act = (Map<String, Object>) jwt.getJWTClaimsSet().getClaim("act");
    assertThat(act).containsEntry("sub", "test-platform-client");
    assertThat(act).containsEntry("type", "PLATFORM_CLIENT");

    // Cryptographic proof the token is real, not just well-formed — signed under a key this
    // Organization's own JWKS actually publishes, same "kid present + signature verifies" bar
    // PlatformTokenIssuanceIntegrationTest's own identical check already holds itself to.
    // Deliberately NOT a /userinfo call: WorkspaceAwareOidcUserInfoMapper unconditionally requires
    // an OidcIdToken to exist on the authorization row, which this v1, access-token-only design
    // never mints — see ImpersonateAccountController's own Javadoc for the real, live-confirmed
    // (400 invalid_request) gap this comment refers to.
    HttpResponse<String> jwksResponse = getJwks(organizationId);
    assertThat(jwksResponse.statusCode()).isEqualTo(200);
    JWKSet jwkSet = JWKSet.parse(jwksResponse.body());
    JWK publishedKey = jwkSet.getKeyByKeyId(jwt.getHeader().getKeyID());
    assertThat(publishedKey)
        .as(
            "the token's own kid must actually be present in this Organization's own published JWKS")
        .isNotNull();
    assertThat(jwt.verify(new RSASSAVerifier(((RSAKey) publishedKey).toRSAPublicKey())))
        .as("cryptographic proof, not just a matching kid")
        .isTrue();

    Integer authorizationRows =
        jdbcTemplate.queryForObject(
            "select count(*) from oauth2_authorization where principal_name = ?",
            Integer.class,
            accountId.toString());
    assertThat(authorizationRows)
        .as("must be revocable via /oauth2/revoke, same as every other token this system issues")
        .isEqualTo(1);

    HttpResponse<String> revokeResponse = revoke(organizationId, client, accessToken);
    assertThat(revokeResponse.statusCode()).isEqualTo(200);
  }

  @Test
  void defaultsToTheClientsOwnFullAllowedScopesWhenNoneAreRequested() throws Exception {
    String platformToken = requestPlatformAccessToken(IMPERSONATE_SCOPE);
    UUID organizationId = createOrganization(platformToken, "Default Scope Co");
    ClientCredentials client =
        registerOAuthClient(platformToken, organizationId, "openid profile email");
    UUID accountId =
        registerAccount(organizationId, "default-scope@example.com", "a-correct-password");

    HttpResponse<String> response = impersonate(platformToken, accountId, client.clientId(), "[]");

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode scope = objectMapper.readTree(response.body()).get("scope");
    assertThat(scope.toString()).contains("openid").contains("profile").contains("email");
  }

  @Test
  void rejectsAScopeTheClientIsNotAllowed() throws Exception {
    String platformToken = requestPlatformAccessToken(IMPERSONATE_SCOPE);
    UUID organizationId = createOrganization(platformToken, "Narrow Scope Co");
    ClientCredentials client = registerOAuthClient(platformToken, organizationId, "openid");
    UUID accountId = registerAccount(organizationId, "narrow@example.com", "a-correct-password");

    HttpResponse<String> response =
        impersonate(platformToken, accountId, client.clientId(), "[\"admin:everything\"]");

    assertThat(response.statusCode()).isEqualTo(400);
  }

  @Test
  void rejectsAClientRegisteredUnderADifferentOrganization() throws Exception {
    String platformToken = requestPlatformAccessToken(IMPERSONATE_SCOPE);
    UUID organizationId = createOrganization(platformToken, "Org A");
    UUID otherOrganizationId = createOrganization(platformToken, "Org B");
    ClientCredentials otherOrgsClient =
        registerOAuthClient(platformToken, otherOrganizationId, "openid");
    UUID accountId =
        registerAccount(organizationId, "cross-tenant@example.com", "a-correct-password");

    HttpResponse<String> response =
        impersonate(platformToken, accountId, otherOrgsClient.clientId(), "[]");

    assertThat(response.statusCode())
        .as("BR-ORG-02/ADR-0010: a client from a different Organization must never be usable here")
        .isEqualTo(400);
  }

  @Test
  void returns409ForASuspendedAccount() throws Exception {
    String platformToken =
        requestPlatformAccessToken(
            "platform:organizations:write platform:accounts:impersonate"
                + " platform:accounts:suspend");
    UUID organizationId = createOrganization(platformToken, "Suspended Co");
    ClientCredentials client = registerOAuthClient(platformToken, organizationId, "openid");
    UUID accountId = registerAccount(organizationId, "suspended@example.com", "a-correct-password");
    HttpRequest suspend =
        HttpRequest.newBuilder(baseUri("/api/v1/admin/accounts/" + accountId + ":suspend"))
            .header("Authorization", "Bearer " + platformToken)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    httpClient.send(suspend, HttpResponse.BodyHandlers.discarding());

    HttpResponse<String> response = impersonate(platformToken, accountId, client.clientId(), "[]");

    assertThat(response.statusCode()).isEqualTo(409);
  }

  @Test
  void returns404ForAnUnknownAccount() throws Exception {
    String platformToken = requestPlatformAccessToken(IMPERSONATE_SCOPE);

    HttpResponse<String> response =
        impersonate(platformToken, UUID.randomUUID(), "irrelevant-client-id", "[]");

    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  void rejectsImpersonationWithoutTheAccountsImpersonateScope() throws Exception {
    String platformTokenWithoutScope = requestPlatformAccessToken("platform:organizations:write");
    UUID organizationId = createOrganization(platformTokenWithoutScope, "No Impersonate Scope Co");
    ClientCredentials client =
        registerOAuthClient(platformTokenWithoutScope, organizationId, "openid");
    UUID accountId = registerAccount(organizationId, "no-scope@example.com", "a-correct-password");

    HttpResponse<String> response =
        impersonate(platformTokenWithoutScope, accountId, client.clientId(), "[]");

    assertThat(response.statusCode()).isEqualTo(403);
  }

  private HttpResponse<String> impersonate(
      String platformToken, UUID accountId, String clientId, String scopesJsonArray)
      throws IOException, InterruptedException {
    String requestBody = "{\"clientId\":\"" + clientId + "\",\"scopes\":" + scopesJsonArray + "}";
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/api/v1/admin/accounts/" + accountId + ":impersonate"))
            .header("Authorization", "Bearer " + platformToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> getJwks(UUID organizationId)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/oauth2/jwks")).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> revoke(
      UUID organizationId, ClientCredentials client, String accessToken)
      throws IOException, InterruptedException {
    // The org-scoped /o/{organizationId}/oauth2/revoke, not the platform tier's root-level one —
    // an OAuthClient's own credentials only ever authenticate against OrganizationRegisteredClient
    // Repository, never PlatformRegisteredClientRepository (real bug this test itself caught: a
    // straight copy of PlatformTokenIssuanceIntegrationTest's own revokeToken helper, which
    // correctly targets the platform tier since that test issues platform tokens).
    String basicAuth =
        Base64.getEncoder()
            .encodeToString((client.clientId() + ":" + client.clientSecret()).getBytes());
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/oauth2/revoke"))
            .header("Authorization", "Basic " + basicAuth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "token=" + accessToken + "&token_type_hint=access_token"))
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

  private ClientCredentials registerOAuthClient(
      String platformToken, UUID organizationId, String allowedScopes)
      throws IOException, InterruptedException {
    String scopesJson =
        allowedScopes.isBlank()
            ? "[]"
            : "[\"" + String.join("\",\"", allowedScopes.split(" ")) + "\"]";
    String requestBody =
        """
        {
          "redirectUris": ["%s"],
          "allowedGrantTypes": ["authorization_code", "refresh_token"],
          "allowedScopes": %s,
          "requireConsent": false
        }
        """
            .formatted(REDIRECT_URI, scopesJson);
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
