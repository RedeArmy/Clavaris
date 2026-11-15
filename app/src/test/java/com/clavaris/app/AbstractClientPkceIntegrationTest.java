package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.PlatformApiTestSupport;
import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.app.support.TestMailSenderConfig;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
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
 * The server, the database and the plumbing the PKCE integration tests share: an Organization with
 * a client and an account, the authorize-and-sign-in dance, and the token exchange. The tests are
 * in {@link ClientPkcePolicyIntegrationTest} and {@link ClientPkceSwitchIntegrationTest}, which
 * differ in the value of {@code clavaris.oauth.pkce.allow-optional} the server starts with.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret",
      "clavaris.rate-limit.token.per-client-limit=500"
    })
abstract class AbstractClientPkceIntegrationTest extends RedisBackedIntegrationTest {

  protected static final String REDIRECT_URI = "https://client.example.test/callback";
  protected static final String PASSWORD = "a-correct-password";
  protected static final Pattern CSRF = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  protected int port;

  @Autowired protected PlatformAccountRepository platformAccounts;
  @Autowired protected JdbcTemplate jdbcTemplate;

  protected final CookieManager cookies = new CookieManager();
  protected final HttpClient http =
      HttpClient.newBuilder()
          .cookieHandler(cookies)
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  protected final ObjectMapper json = new ObjectMapper();
  protected final SecureRandom random = new SecureRandom();

  protected record Client(UUID organizationId, String clientId, String clientSecret) {}

  /** An Organization with one client (policy as given, or omitted when null) and one account. */
  protected Client clientWith(final String policyJson, final String email) throws Exception {
    String platformToken =
        PlatformApiTestSupport.platformAccessToken(
            http, json, uri("/oauth2/token"), "platform:organizations:write");
    UUID organizationId = createOrganization(platformToken);
    HttpResponse<String> response = registerClient(platformToken, organizationId, policyJson);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    JsonNode created = json.readTree(response.body());
    registerAccount(organizationId, email);
    return new Client(
        organizationId, created.get("clientId").asString(), created.get("clientSecret").asString());
  }

  /** The registration request itself, for a test that expects it to be refused. */
  protected HttpResponse<String> registerClient(
      final String platformToken, final UUID organizationId, final String policyJson)
      throws Exception {
    String body =
        """
        {"redirectUris": ["%s"], "allowedGrantTypes": ["authorization_code"],
         "allowedScopes": ["openid"], "requireConsent": false%s}
        """
            .formatted(REDIRECT_URI, policyJson == null ? "" : ", " + policyJson);
    return http.send(
        HttpRequest.newBuilder(uri("/api/v1/admin/organizations/" + organizationId + "/clients"))
            .header("Authorization", "Bearer " + platformToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /** Authorize (with a challenge when given), sign in, and return where the request ended. */
  protected String authorizeAndSignIn(
      final Client client, final String challenge, final String email) throws Exception {
    String query =
        "response_type=code&client_id="
            + client.clientId()
            + "&redirect_uri="
            + enc(REDIRECT_URI)
            + "&scope=openid&state=s"
            + (challenge == null
                ? ""
                : "&code_challenge=" + challenge + "&code_challenge_method=S256");
    HttpResponse<Void> first =
        http.send(
            HttpRequest.newBuilder(
                    uri("/o/" + client.organizationId() + "/oauth2/authorize?" + query))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.discarding());
    String location = first.headers().firstValue("Location").orElseThrow();
    if (location.startsWith(REDIRECT_URI)) {
      return location;
    }
    String csrf = loginCsrf(client.organizationId());
    HttpResponse<Void> login =
        http.send(
            HttpRequest.newBuilder(uri("/o/" + client.organizationId() + "/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "_csrf=" + csrf + "&email=" + email + "&password=" + PASSWORD))
                .build(),
            HttpResponse.BodyHandlers.discarding());
    HttpResponse<Void> resumed =
        http.send(
            HttpRequest.newBuilder(URI.create(login.headers().firstValue("Location").orElseThrow()))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.discarding());
    return resumed.headers().firstValue("Location").orElseThrow();
  }

  protected HttpResponse<String> exchange(
      final Client client, final String code, final String verifier)
      throws IOException, InterruptedException {
    String basic =
        Base64.getEncoder()
            .encodeToString((client.clientId() + ":" + client.clientSecret()).getBytes());
    return http.send(
        HttpRequest.newBuilder(uri("/o/" + client.organizationId() + "/oauth2/token"))
            .header("Authorization", "Basic " + basic)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "grant_type=authorization_code&code="
                        + code
                        + "&redirect_uri="
                        + enc(REDIRECT_URI)
                        + (verifier == null ? "" : "&code_verifier=" + verifier)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  // --- plumbing ---------------------------------------------------------------------------------

  protected String storedPolicy(final Client client) {
    return jdbcTemplate.queryForObject(
        "SELECT pkce_policy FROM oauth_clients WHERE client_id = ?",
        String.class,
        client.clientId());
  }

  protected UUID createOrganization(final String platformToken)
      throws IOException, InterruptedException {
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(uri("/api/v1/admin/organizations"))
                .header("Authorization", "Bearer " + platformToken)
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"name\":\"Pkce Co "
                            + UUID.randomUUID().toString().substring(0, 8)
                            + "\",\"ownerPlatformAccountId\":\""
                            + PlatformApiTestSupport.registerAPlatformAccount(platformAccounts)
                            + "\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return UUID.fromString(json.readTree(response.body()).get("id").asString());
  }

  protected void registerAccount(final UUID organizationId, final String email)
      throws IOException, InterruptedException {
    HttpResponse<String> form =
        http.send(
            HttpRequest.newBuilder(uri("/o/" + organizationId + "/register")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    Matcher matcher = CSRF.matcher(form.body());
    assertThat(matcher.find()).isTrue();
    HttpResponse<Void> response =
        http.send(
            HttpRequest.newBuilder(uri("/o/" + organizationId + "/register"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "_csrf="
                            + matcher.group(1)
                            + "&email="
                            + email
                            + "&password="
                            + PASSWORD
                            + "&confirmPassword="
                            + PASSWORD))
                .build(),
            HttpResponse.BodyHandlers.discarding());
    assertThat(response.statusCode()).isEqualTo(302);
  }

  protected String loginCsrf(final UUID organizationId) throws IOException, InterruptedException {
    HttpResponse<String> page =
        http.send(
            HttpRequest.newBuilder(uri("/o/" + organizationId + "/login")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    Matcher matcher = CSRF.matcher(page.body());
    assertThat(matcher.find()).as("the login page renders a CSRF token").isTrue();
    return matcher.group(1);
  }

  protected URI uri(final String path) {
    return URI.create("http://localhost:" + port + path);
  }

  protected String verifier() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  protected static String challenge(final String verifier) {
    try {
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(
              MessageDigest.getInstance("SHA-256")
                  .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  protected static String enc(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  protected static String queryParam(final String url, final String name) {
    String query = URI.create(url).getRawQuery();
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq >= 0 && pair.substring(0, eq).equals(name)) {
        return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }
}
