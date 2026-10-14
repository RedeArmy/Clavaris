package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.app.support.TestMailSenderConfig;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccount;
import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
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
import java.util.List;
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
 * Real bug found live, 2026-10-14: every tenant shared the one {@code SESSION} cookie, so signing
 * in to a second Organization in the same browser reused the first one's {@code HttpSession} and
 * overwrote its {@code SecurityContext}, signing the first one out. These tests drive two real
 * Organizations through one shared cookie jar (one browser) and prove each keeps its own session —
 * and that the flat consent page, which carries no Organization in its path, still finds the right
 * one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret"
    })
class TenantSessionIsolationIntegrationTest extends RedisBackedIntegrationTest {

  private static final String REDIRECT_URI = "https://client.example.test/callback";
  private static final String PASSWORD = "a-correct-password";
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
  void signingInToASecondTenantKeepsTheFirstTenantSignedIn() throws Exception {
    UUID tenantA = createOrganization("Isolation A " + UUID.randomUUID());
    UUID tenantB = createOrganization("Isolation B " + UUID.randomUUID());
    signUp(tenantA, "a-user@example.com");
    signUp(tenantB, "b-user@example.com");

    signIn(tenantA, "a-user@example.com");
    assertThat(profileStatus(tenantA)).isEqualTo(200);

    signIn(tenantB, "b-user@example.com");

    assertThat(profileStatus(tenantA))
        .as("tenant A must still be signed in after tenant B signed in, in the same browser")
        .isEqualTo(200);
    assertThat(profileStatus(tenantB)).isEqualTo(200);
    assertThat(cookieNames())
        .contains(cookieName(tenantA), cookieName(tenantB))
        .doesNotContain("SESSION");
  }

  @Test
  void signingOutOfOneTenantLeavesTheOtherSignedIn() throws Exception {
    UUID tenantA = createOrganization("Logout A " + UUID.randomUUID());
    UUID tenantB = createOrganization("Logout B " + UUID.randomUUID());
    signUp(tenantA, "a-out@example.com");
    signUp(tenantB, "b-out@example.com");
    signIn(tenantA, "a-out@example.com");
    signIn(tenantB, "b-out@example.com");

    String token = confirmationToken(get("/o/" + tenantB + "/connect/logout").body());
    HttpResponse<String> loggedOut =
        post("/o/" + tenantB + "/connect/logout", "confirmation_token=" + token);

    assertThat(loggedOut.body()).contains("Signed out");
    assertThat(profileStatus(tenantB)).as("tenant B is signed out").isEqualTo(302);
    assertThat(profileStatus(tenantA)).as("tenant A is untouched").isEqualTo(200);
  }

  // /oauth2/consent is flat (Spring Authorization Server fixes the URL), so the Organization comes
  // from client_id: with both tenants signed in, the consent screen and its approval must still
  // belong to the tenant of the client being authorized.
  @Test
  void theFlatConsentPageStillFindsTheRightTenantWhenTwoAreSignedIn() throws Exception {
    UUID tenantA = createOrganization("Consent A " + UUID.randomUUID());
    UUID tenantB = createOrganization("Consent B " + UUID.randomUUID());
    // Consent is only asked for a scope beyond openid, so the client also requests profile.
    String clientB = registerClientRequiringConsent(tenantB);
    signUp(tenantA, "a-consent@example.com");
    signUp(tenantB, "b-consent@example.com");
    signIn(tenantB, "b-consent@example.com");
    signIn(tenantA, "a-consent@example.com");

    String verifier = codeVerifier();
    String authorize =
        "/o/"
            + tenantB
            + "/oauth2/authorize?response_type=code&client_id="
            + clientB
            + "&redirect_uri="
            + urlEncode(REDIRECT_URI)
            + "&scope=openid%20profile&code_challenge="
            + challenge(verifier)
            + "&code_challenge_method=S256&state=consent-state";
    HttpResponse<String> toConsent = get(authorize);
    String consentUrl = toConsent.headers().firstValue("Location").orElseThrow();
    assertThat(consentUrl).contains("/oauth2/consent");

    HttpResponse<String> consentPage = getAbsolute(consentUrl);
    assertThat(consentPage.statusCode()).isEqualTo(200);

    // The authorize endpoint is exempt from CSRF; the consent state is the one SAS put in the URL.
    HttpResponse<String> approved =
        post(
            "/o/" + tenantB + "/oauth2/authorize",
            "client_id="
                + clientB
                + "&state="
                + urlEncode(queryParam(consentUrl, "state"))
                + "&scope=profile");
    assertThat(approved.headers().firstValue("Location").orElse(""))
        .as("approving consent for tenant B's client, with tenant A also signed in, issues a code")
        .contains("code=");
    assertThat(profileStatus(tenantA)).isEqualTo(200);
  }

  // Sessions from before the per-tenant cookies live under SESSION: a stale one in the jar must be
  // ignored (no 500, no loop) and must not stop a normal sign-in.
  @Test
  void anOldSharedSessionCookieIsIgnored() throws Exception {
    UUID tenantA = createOrganization("Migration A " + UUID.randomUUID());
    signUp(tenantA, "migrate@example.com");
    HttpCookie stale =
        new HttpCookie("SESSION", Base64.getEncoder().encodeToString("stale".getBytes()));
    stale.setPath("/");
    cookieManager.getCookieStore().add(URI.create("http://localhost:" + port + "/"), stale);

    assertThat(profileStatus(tenantA)).as("unauthenticated, not an error").isEqualTo(302);
    signIn(tenantA, "migrate@example.com");

    assertThat(profileStatus(tenantA)).isEqualTo(200);
  }

  @Test
  void theTenantCookiesStayApartFromThePlatformOne() throws Exception {
    UUID tenantA = createOrganization("Platform Apart " + UUID.randomUUID());

    get("/platform/login");
    get("/o/" + tenantA + "/login");

    assertThat(cookieNames()).contains("PLATFORM_SESSION", cookieName(tenantA));
  }

  private static String cookieName(UUID organizationId) {
    return "SESSION_" + organizationId.toString().replace("-", "");
  }

  private List<String> cookieNames() {
    return cookieManager.getCookieStore().getCookies().stream().map(HttpCookie::getName).toList();
  }

  private int profileStatus(UUID organizationId) throws Exception {
    return get("/o/" + organizationId + "/account/profile").statusCode();
  }

  private void signUp(UUID organizationId, String email) throws Exception {
    String csrf = csrf(get("/o/" + organizationId + "/register").body());
    HttpResponse<String> response =
        post(
            "/o/" + organizationId + "/register",
            "_csrf="
                + csrf
                + "&email="
                + email
                + "&password="
                + PASSWORD
                + "&confirmPassword="
                + PASSWORD);
    assertThat(response.statusCode()).isEqualTo(302);
    jdbcTemplate.update(
        "update accounts set email_verified_at = now() where organization_id = ? and email = ?",
        organizationId,
        email);
  }

  private void signIn(UUID organizationId, String email) throws Exception {
    String csrf = csrf(get("/o/" + organizationId + "/login").body());
    HttpResponse<String> response =
        post(
            "/o/" + organizationId + "/login",
            "_csrf=" + csrf + "&email=" + email + "&password=" + PASSWORD);
    assertThat(response.statusCode()).as("sign-in redirects on success").isEqualTo(302);
  }

  private String registerClientRequiringConsent(UUID organizationId) throws Exception {
    String body =
        """
        {"redirectUris": ["%s"], "allowedGrantTypes": ["authorization_code"],
         "allowedScopes": ["openid", "profile"], "requireConsent": true}
        """
            .formatted(REDIRECT_URI);
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(
                    uri("/api/v1/admin/organizations/" + organizationId + "/clients"))
                .header("Authorization", "Bearer " + platformToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    return objectMapper.readTree(response.body()).get("clientId").asString();
  }

  private UUID createOrganization(String name) throws Exception {
    PlatformAccount owner =
        PlatformAccount.register(new Email("owner-" + UUID.randomUUID() + "@example.test"));
    owner.attachPasswordCredential("not-a-real-hash-this-test-never-logs-in");
    platformAccounts.save(owner);
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(uri("/api/v1/admin/organizations"))
                .header("Authorization", "Bearer " + platformToken())
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"name\":\""
                            + name
                            + "\",\"ownerPlatformAccountId\":\""
                            + owner.id().value()
                            + "\"}"))
                .build());
    JsonNode created = objectMapper.readTree(response.body());
    return UUID.fromString(created.get("id").asString());
  }

  private String platformToken() throws Exception {
    String basic =
        Base64.getEncoder()
            .encodeToString("test-platform-client:a-test-platform-secret".getBytes());
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(uri("/oauth2/token"))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "grant_type=client_credentials&scope=platform:organizations:write"))
                .build());
    return objectMapper.readTree(response.body()).get("access_token").asString();
  }

  private HttpResponse<String> get(String path) throws Exception {
    return send(HttpRequest.newBuilder(uri(path)).GET().build());
  }

  private HttpResponse<String> getAbsolute(String absoluteUrl) throws Exception {
    return send(HttpRequest.newBuilder(URI.create(absoluteUrl)).GET().build());
  }

  private HttpResponse<String> post(String path, String formBody) throws Exception {
    return send(
        HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(formBody))
            .build());
  }

  private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static String csrf(String html) {
    Matcher matcher = CSRF_TOKEN_PATTERN.matcher(html);
    assertThat(matcher.find()).as("page must render a _csrf hidden input").isTrue();
    return matcher.group(1);
  }

  private static String confirmationToken(String html) {
    Matcher matcher =
        Pattern.compile("name=\"confirmation_token\" value=\"([^\"]+)\"").matcher(html);
    assertThat(matcher.find()).as("the page must carry a confirmation token").isTrue();
    return matcher.group(1);
  }

  private String codeVerifier() {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String challenge(String verifier) throws NoSuchAlgorithmException {
    byte[] hash =
        MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
    return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
  }

  private static String queryParam(String url, String name) {
    for (String pair : URI.create(url).getRawQuery().split("&")) {
      int equalsIndex = pair.indexOf('=');
      if (equalsIndex >= 0 && pair.substring(0, equalsIndex).equals(name)) {
        return java.net.URLDecoder.decode(pair.substring(equalsIndex + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  private static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }
}
