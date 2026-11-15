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
 * ADR-0043: PKCE as a per-client policy, against the running server. What the OpenID Foundation's
 * Basic OP plan needs (a confidential client that sends no PKCE) works for a client that chose
 * OPTIONAL and stays refused for the default, and choosing OPTIONAL never switches off a check on
 * what a request did send.
 *
 * <p>The authorization request is built here with or without a {@code code_challenge}, and the code
 * is exchanged with or without a {@code code_verifier}, so each case is one cell of the table the
 * ADR gives: policy by challenge by verifier.
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
class ClientPkcePolicyIntegrationTest extends RedisBackedIntegrationTest {

  private static final String REDIRECT_URI = "https://client.example.test/callback";
  private static final String PASSWORD = "a-correct-password";
  private static final Pattern CSRF = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Autowired private PlatformAccountRepository platformAccounts;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final CookieManager cookies = new CookieManager();
  private final HttpClient http =
      HttpClient.newBuilder()
          .cookieHandler(cookies)
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final ObjectMapper json = new ObjectMapper();
  private final SecureRandom random = new SecureRandom();

  private record Client(UUID organizationId, String clientId, String clientSecret) {}

  /** An Organization with one client (policy as given, or omitted when null) and one account. */
  private Client clientWith(final String policyJson, final String email) throws Exception {
    String platformToken =
        PlatformApiTestSupport.platformAccessToken(
            http, json, uri("/oauth2/token"), "platform:organizations:write");
    UUID organizationId = createOrganization(platformToken);
    String body =
        """
        {"redirectUris": ["%s"], "allowedGrantTypes": ["authorization_code"],
         "allowedScopes": ["openid"], "requireConsent": false%s}
        """
            .formatted(REDIRECT_URI, policyJson == null ? "" : ", " + policyJson);
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(
                    uri("/api/v1/admin/organizations/" + organizationId + "/clients"))
                .header("Authorization", "Bearer " + platformToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    JsonNode created = json.readTree(response.body());
    registerAccount(organizationId, email);
    return new Client(
        organizationId, created.get("clientId").asString(), created.get("clientSecret").asString());
  }

  /** Authorize (with a challenge when given), sign in, and return where the request ended. */
  private String authorizeAndSignIn(final Client client, final String challenge, final String email)
      throws Exception {
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

  private HttpResponse<String> exchange(
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

  // --- the default stays strict -------------------------------------------------------------

  @Test
  void aClientRegisteredWithoutAPolicyRequiresPkceAndRefusesARequestWithNoChallenge()
      throws Exception {
    Client client = clientWith(null, "default@example.test");

    String location = authorizeAndSignIn(client, null, "default@example.test");

    assertThat(location).startsWith(REDIRECT_URI).contains("error=invalid_request");
    assertThat(location).contains("code_challenge");
    assertThat(storedPolicy(client)).isEqualTo("REQUIRED");
  }

  @Test
  void aRequiredClientStillCompletesTheFlowWithAChallengeAndItsVerifier() throws Exception {
    Client client = clientWith("\"pkcePolicy\": \"REQUIRED\"", "required@example.test");
    String verifier = verifier();

    String location = authorizeAndSignIn(client, challenge(verifier), "required@example.test");

    assertThat(exchange(client, queryParam(location, "code"), verifier).statusCode())
        .isEqualTo(200);
  }

  // --- OPTIONAL: what changes ---------------------------------------------------------------

  @Test
  void anOptionalClientCompletesTheWholeFlowWithNoPkceAtAll() throws Exception {
    Client client = clientWith("\"pkcePolicy\": \"OPTIONAL\"", "optional@example.test");

    String location = authorizeAndSignIn(client, null, "optional@example.test");

    String code = queryParam(location, "code");
    assertThat(code).as("an authorization code, not an error: %s", location).isNotBlank();
    HttpResponse<String> token = exchange(client, code, null);
    assertThat(token.statusCode()).as(token.body()).isEqualTo(200);
    assertThat(json.readTree(token.body()).get("id_token")).isNotNull();
    assertThat(storedPolicy(client)).isEqualTo("OPTIONAL");
  }

  // --- OPTIONAL: what does not change ---------------------------------------------------------

  @Test
  void anOptionalClientThatDoesSendAChallengeStillMustProveItWithTheVerifier() throws Exception {
    Client client = clientWith("\"pkcePolicy\": \"OPTIONAL\"", "opt-wrong@example.test");
    String location = authorizeAndSignIn(client, challenge(verifier()), "opt-wrong@example.test");

    HttpResponse<String> token = exchange(client, queryParam(location, "code"), verifier());

    assertThat(token.statusCode()).isEqualTo(400);
    assertThat(token.body()).contains("invalid_grant");
  }

  @Test
  void anOptionalClientThatSendsAChallengeButNoVerifierIsRefused() throws Exception {
    Client client = clientWith("\"pkcePolicy\": \"OPTIONAL\"", "opt-none@example.test");
    String location = authorizeAndSignIn(client, challenge(verifier()), "opt-none@example.test");

    HttpResponse<String> token = exchange(client, queryParam(location, "code"), null);

    assertThat(token.statusCode()).isEqualTo(400);
  }

  @Test
  void aVerifierSentWithACodeThatHadNoChallengeIsADowngradeAndIsRefused() throws Exception {
    // RFC 9700 4.8: the code was issued with nothing to verify, so presenting a verifier must not
    // be
    // read as if PKCE had been in force. Spring Authorization Server refuses it itself, while it
    // authenticates the client (ClientSecretAuthenticationProvider), which is why this change adds
    // no
    // code of its own for it: this test is what keeps that true if the library or the way the
    // endpoint is wired ever changes.
    Client client = clientWith("\"pkcePolicy\": \"OPTIONAL\"", "downgrade@example.test");
    String location = authorizeAndSignIn(client, null, "downgrade@example.test");

    HttpResponse<String> token = exchange(client, queryParam(location, "code"), verifier());

    assertThat(token.statusCode()).isEqualTo(400);
    assertThat(token.body()).contains("code_challenge");
  }

  @Test
  void theSameDowngradeIsRefusedWhenTheSecretTravelsInTheBodyInsteadOfTheHeader() throws Exception {
    Client client = clientWith("\"pkcePolicy\": \"OPTIONAL\"", "downgrade-post@example.test");
    String code =
        queryParam(authorizeAndSignIn(client, null, "downgrade-post@example.test"), "code");

    HttpResponse<String> token =
        http.send(
            HttpRequest.newBuilder(uri("/o/" + client.organizationId() + "/oauth2/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "grant_type=authorization_code&code="
                            + code
                            + "&redirect_uri="
                            + enc(REDIRECT_URI)
                            + "&client_id="
                            + enc(client.clientId())
                            + "&client_secret="
                            + enc(client.clientSecret())
                            + "&code_verifier="
                            + verifier()))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(token.statusCode()).isEqualTo(400);
    assertThat(token.body()).contains("code_challenge");
  }

  @Test
  void theChallengeMethodPlainIsStillRefusedWhateverThePolicy() throws Exception {
    Client client = clientWith("\"pkcePolicy\": \"OPTIONAL\"", "plain@example.test");
    String verifier = verifier();
    String query =
        "response_type=code&client_id="
            + client.clientId()
            + "&redirect_uri="
            + enc(REDIRECT_URI)
            + "&scope=openid&state=s&code_challenge="
            + verifier
            + "&code_challenge_method=plain";

    HttpResponse<Void> response =
        http.send(
            HttpRequest.newBuilder(
                    uri("/o/" + client.organizationId() + "/oauth2/authorize?" + query))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.discarding());

    String location = response.headers().firstValue("Location").orElseThrow();
    assertThat(location).startsWith(REDIRECT_URI).contains("error=invalid_request");
  }

  // --- the policy on the way in -----------------------------------------------------------------

  @Test
  void aPolicyThatIsNeitherValueIsRefusedAtRegistration() throws Exception {
    String platformToken =
        PlatformApiTestSupport.platformAccessToken(
            http, json, uri("/oauth2/token"), "platform:organizations:write");
    UUID organizationId = createOrganization(platformToken);

    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(
                    uri("/api/v1/admin/organizations/" + organizationId + "/clients"))
                .header("Authorization", "Bearer " + platformToken)
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"redirectUris\":[\""
                            + REDIRECT_URI
                            + "\"],\"allowedGrantTypes\":"
                            + "[\"authorization_code\"],\"allowedScopes\":[\"openid\"],"
                            + "\"pkcePolicy\":\"NONE\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
  }

  @Test
  void theRegistrationResponseAndTheAuditLogSayWhichPolicyAClientWasBornWith() throws Exception {
    Client client = clientWith("\"pkcePolicy\": \"OPTIONAL\"", "audit@example.test");

    String detail =
        jdbcTemplate.queryForObject(
            "SELECT detail FROM audit_events WHERE action = 'oauth_client.registered'"
                + " AND detail LIKE ? ORDER BY occurred_at DESC LIMIT 1",
            String.class,
            "%" + client.clientId() + "%");

    assertThat(detail).contains("pkcePolicy=OPTIONAL");
  }

  // --- plumbing ---------------------------------------------------------------------------------

  private String storedPolicy(final Client client) {
    return jdbcTemplate.queryForObject(
        "SELECT pkce_policy FROM oauth_clients WHERE client_id = ?",
        String.class,
        client.clientId());
  }

  private UUID createOrganization(final String platformToken)
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

  private void registerAccount(final UUID organizationId, final String email)
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

  private String loginCsrf(final UUID organizationId) throws IOException, InterruptedException {
    HttpResponse<String> page =
        http.send(
            HttpRequest.newBuilder(uri("/o/" + organizationId + "/login")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    Matcher matcher = CSRF.matcher(page.body());
    assertThat(matcher.find()).as("the login page renders a CSRF token").isTrue();
    return matcher.group(1);
  }

  private URI uri(final String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private String verifier() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String challenge(final String verifier) {
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

  private static String enc(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String queryParam(final String url, final String name) {
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
