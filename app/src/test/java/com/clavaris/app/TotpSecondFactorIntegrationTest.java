package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.PlatformApiTestSupport;
import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.app.support.TestMailSenderConfig;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.domain.service.Base32;
import com.clavaris.identity.domain.service.TotpAlgorithm;
import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
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
import tools.jackson.databind.ObjectMapper;

/**
 * ADR-0040: the authenticator-app second factor end to end against real Postgres and Redis. An
 * Account adds an app from its own page, signing in then stops at the code page and builds no
 * session until the code is right, a code or a recovery code works once, switching the Organization
 * off or an operator reset lets the Account in again, and an Account without an app is never asked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret",
      // The policy is cached for 30 seconds by default; the switch tests flip it and sign in at
      // once.
      "clavaris.auth.policy-cache-ttl-seconds=0"
    })
class TotpSecondFactorIntegrationTest extends RedisBackedIntegrationTest {

  private static final Pattern CSRF_TOKEN_PATTERN =
      Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");
  private static final Pattern SECRET_PATTERN = Pattern.compile("<code>([A-Z2-7 ]+)</code>");
  private static final Pattern RECOVERY_CODE_PATTERN =
      Pattern.compile("<code>([A-Z2-7]{4}(?:-[A-Z2-7]{4}){3})</code>");
  private static final String PASSWORD = "a-correct-password";
  private static final String SCOPES =
      "platform:organizations:write platform:account-authentication-policy:write"
          + " platform:accounts:totp-reset";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Autowired private PlatformAccountRepository platformAccounts;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final HttpClient apiClient = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper = new ObjectMapper();

  /** One person's browser: its own cookies, so each can be signed in or out on its own. */
  private final class Browser {
    private final CookieManager cookies = new CookieManager();
    private final HttpClient client =
        HttpClient.newBuilder()
            .cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    HttpResponse<String> get(final String path) throws Exception {
      return client.send(
          HttpRequest.newBuilder(baseUri(path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(final String path, final String csrfPage, final String fields)
        throws Exception {
      final HttpResponse<String> page = get(csrfPage);
      final String csrf = csrf(page.body());
      return client.send(
          HttpRequest.newBuilder(baseUri(path))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + csrf + fields))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }

    /** Submits a form whose token is on the page this very response came from. */
    HttpResponse<String> postWithToken(
        final String path, final String csrfToken, final String fields) throws Exception {
      return client.send(
          HttpRequest.newBuilder(baseUri(path))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + csrfToken + fields))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> signIn(final UUID organizationId, final String email) throws Exception {
      return post(
          "/o/" + organizationId + "/login",
          "/o/" + organizationId + "/login",
          "&email=" + enc(email) + "&password=" + enc(PASSWORD));
    }

    boolean isSignedIn(final UUID organizationId) throws Exception {
      return get("/o/" + organizationId + "/account/two-factor").statusCode() == 200;
    }
  }

  private static String csrf(final String body) {
    final Matcher matcher = CSRF_TOKEN_PATTERN.matcher(body);
    assertThat(matcher.find()).as("the page must render a _csrf hidden input").isTrue();
    return matcher.group(1);
  }

  private static String enc(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String location(final HttpResponse<?> response) {
    return response.headers().firstValue("Location").orElse("");
  }

  private static String codeAtStepOffset(final String groupedSecret, final int offset) {
    return TotpAlgorithm.codeAt(
        Base32.decode(groupedSecret),
        TotpAlgorithm.stepAt(Instant.now().getEpochSecond()) + offset);
  }

  /** An Organization that allows the app and has one Account, which has added an app. */
  private record Enrolled(
      UUID organizationId, String email, String secret, List<String> recoveryCodes) {}

  private Enrolled organizationWithEnrolledAccount(final String name, final String email)
      throws Exception {
    final String token = platformToken();
    final UUID organizationId = createProductionOrganization(token, name);
    setTotpEnabled(token, organizationId, true);
    register(organizationId, email);

    final Browser browser = new Browser();
    assertThat(location(browser.signIn(organizationId, email))).contains("/login?authenticated");
    final String page = "/o/" + organizationId + "/account/two-factor";
    final HttpResponse<String> setup = browser.post(page + "/setup", page, "");
    assertThat(setup.statusCode()).isEqualTo(200);
    final Matcher secretMatcher = SECRET_PATTERN.matcher(setup.body());
    assertThat(secretMatcher.find()).as("the setup page shows the key").isTrue();
    final String secret = secretMatcher.group(1);

    final HttpResponse<String> confirmed =
        browser.postWithToken(
            page + "/confirm", csrf(setup.body()), "&code=" + codeAtStepOffset(secret, 0));
    assertThat(confirmed.statusCode()).isEqualTo(200);
    final List<String> codes = new ArrayList<>();
    final Matcher codeMatcher = RECOVERY_CODE_PATTERN.matcher(confirmed.body());
    while (codeMatcher.find()) {
      codes.add(codeMatcher.group(1));
    }
    assertThat(codes).as("ten recovery codes are shown once").hasSize(10);
    return new Enrolled(organizationId, email, secret, codes);
  }

  @Test
  void signingInWithAnEnrolledAccountStopsAtTheCodePageAndBuildsNoSession() throws Exception {
    final Enrolled enrolled = organizationWithEnrolledAccount("Totp Pause Co", "pause@example.com");
    final Browser browser = new Browser();

    final HttpResponse<String> signIn = browser.signIn(enrolled.organizationId(), enrolled.email());

    assertThat(location(signIn)).endsWith("/o/" + enrolled.organizationId() + "/login/totp");
    assertThat(browser.isSignedIn(enrolled.organizationId()))
        .as("nothing is signed in until the code is right")
        .isFalse();
  }

  @Test
  void aWrongCodeIsRefusedAndTheRightOneSignsIn() throws Exception {
    final Enrolled enrolled = organizationWithEnrolledAccount("Totp Code Co", "code@example.com");
    final Browser browser = new Browser();
    browser.signIn(enrolled.organizationId(), enrolled.email());
    final String totpPage = "/o/" + enrolled.organizationId() + "/login/totp";

    final HttpResponse<String> wrong = browser.post(totpPage, totpPage, "&code=000000");
    assertThat(wrong.statusCode()).isEqualTo(200);
    assertThat(wrong.body()).contains("That code is not valid");
    assertThat(browser.isSignedIn(enrolled.organizationId())).isFalse();

    // The confirming code spent this step; the next one is inside the window the server forgives.
    final HttpResponse<String> right =
        browser.post(totpPage, totpPage, "&code=" + codeAtStepOffset(enrolled.secret(), 1));
    // Back to the page the person was heading for before the sign-in interrupted them.
    assertThat(right.statusCode()).isEqualTo(302);
    assertThat(location(right)).contains("/account/two-factor");
    assertThat(browser.isSignedIn(enrolled.organizationId())).isTrue();
  }

  @Test
  void aCodeThatSignedSomeoneInCannotBeUsedAgain() throws Exception {
    final Enrolled enrolled =
        organizationWithEnrolledAccount("Totp Replay Co", "replay@example.com");
    final String code = codeAtStepOffset(enrolled.secret(), 1);
    final String totpPage = "/o/" + enrolled.organizationId() + "/login/totp";
    final Browser first = new Browser();
    first.signIn(enrolled.organizationId(), enrolled.email());
    assertThat(first.post(totpPage, totpPage, "&code=" + code).statusCode()).isEqualTo(302);

    final Browser second = new Browser();
    second.signIn(enrolled.organizationId(), enrolled.email());
    final HttpResponse<String> replay = second.post(totpPage, totpPage, "&code=" + code);

    assertThat(replay.statusCode()).isEqualTo(200);
    assertThat(second.isSignedIn(enrolled.organizationId())).isFalse();
  }

  @Test
  void aRecoveryCodeSignsInOnceAndOnlyOnce() throws Exception {
    final Enrolled enrolled =
        organizationWithEnrolledAccount("Totp Recovery Co", "recover@example.com");
    final String recoveryCode = enrolled.recoveryCodes().get(0);
    final String totpPage = "/o/" + enrolled.organizationId() + "/login/totp";
    final Browser first = new Browser();
    first.signIn(enrolled.organizationId(), enrolled.email());
    assertThat(first.post(totpPage, totpPage, "&code=" + enc(recoveryCode)).statusCode())
        .isEqualTo(302);
    assertThat(first.isSignedIn(enrolled.organizationId())).isTrue();

    final Browser second = new Browser();
    second.signIn(enrolled.organizationId(), enrolled.email());
    final HttpResponse<String> reused =
        second.post(totpPage, totpPage, "&code=" + enc(recoveryCode));

    assertThat(reused.statusCode()).isEqualTo(200);
    assertThat(second.isSignedIn(enrolled.organizationId())).isFalse();
  }

  @Test
  void theCodePageIsOnlyReachableFromAPausedSignIn() throws Exception {
    final Enrolled enrolled =
        organizationWithEnrolledAccount("Totp Direct Co", "direct@example.com");
    final Browser stranger = new Browser();

    final HttpResponse<String> page =
        stranger.get("/o/" + enrolled.organizationId() + "/login/totp");

    assertThat(page.statusCode()).isEqualTo(302);
    assertThat(location(page)).endsWith("/o/" + enrolled.organizationId() + "/login");
  }

  @Test
  void anAccountThatNeverAddedAnAppIsNeverAsked() throws Exception {
    final String token = platformToken();
    final UUID organizationId = createProductionOrganization(token, "Totp Optional Co");
    setTotpEnabled(token, organizationId, true);
    register(organizationId, "optional@example.com");

    final Browser browser = new Browser();
    final HttpResponse<String> signIn = browser.signIn(organizationId, "optional@example.com");

    assertThat(location(signIn)).contains("/login?authenticated");
    assertThat(browser.isSignedIn(organizationId)).isTrue();
  }

  @Test
  void switchingTheOrganizationOffStopsAskingWithoutDeletingTheApp() throws Exception {
    final Enrolled enrolled =
        organizationWithEnrolledAccount("Totp Switch Co", "switch@example.com");
    final String token = platformToken();
    setTotpEnabled(token, enrolled.organizationId(), false);

    final Browser off = new Browser();
    assertThat(location(off.signIn(enrolled.organizationId(), enrolled.email())))
        .contains("/login?authenticated");

    setTotpEnabled(token, enrolled.organizationId(), true);
    final Browser on = new Browser();
    assertThat(location(on.signIn(enrolled.organizationId(), enrolled.email())))
        .as("the app was kept, so turning the switch back on asks again")
        .endsWith("/login/totp");
  }

  @Test
  void anOperatorResetLetsALockedOutAccountInAndAddAgain() throws Exception {
    final Enrolled enrolled = organizationWithEnrolledAccount("Totp Reset Co", "reset@example.com");
    final UUID accountId =
        jdbcTemplate.queryForObject(
            "select id from accounts where email = ?", UUID.class, enrolled.email());

    final HttpResponse<String> reset =
        apiClient.send(
            HttpRequest.newBuilder(baseUri("/api/v1/admin/accounts/" + accountId + ":reset-totp"))
                .header("Authorization", "Bearer " + platformToken())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(reset.statusCode()).isEqualTo(204);

    final Browser browser = new Browser();
    assertThat(location(browser.signIn(enrolled.organizationId(), enrolled.email())))
        .contains("/login?authenticated");
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from totp_recovery_codes where account_id = ?",
                Long.class,
                accountId))
        .isZero();
  }

  @Test
  void anOperatorResetOfAnUnknownAccountIsNotFound() throws Exception {
    final HttpResponse<String> reset =
        apiClient.send(
            HttpRequest.newBuilder(
                    baseUri("/api/v1/admin/accounts/" + UUID.randomUUID() + ":reset-totp"))
                .header("Authorization", "Bearer " + platformToken())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(reset.statusCode()).isEqualTo(404);
  }

  @Test
  void theAppCannotBeAddedWhereTheOrganizationHasNotAllowedIt() throws Exception {
    final String token = platformToken();
    final UUID organizationId = createProductionOrganization(token, "Totp Off Co");
    register(organizationId, "off@example.com");
    final Browser browser = new Browser();
    browser.signIn(organizationId, "off@example.com");

    final String page = "/o/" + organizationId + "/account/two-factor";
    final HttpResponse<String> setup =
        browser.post(page + "/setup", "/o/" + organizationId + "/account/profile", "");

    assertThat(setup.body()).contains("not available for this account");
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from totp_credentials tc join accounts a on a.id = tc.account_id"
                    + " where a.organization_id = ?",
                Long.class,
                organizationId))
        .isZero();
  }

  @Test
  void theSecretIsStoredEncryptedAndTheRecoveryCodesOnlyAsHashes() throws Exception {
    final Enrolled enrolled = organizationWithEnrolledAccount("Totp Rest Co", "rest@example.com");

    final String stored =
        jdbcTemplate.queryForObject(
            "select tc.secret_encrypted from totp_credentials tc join accounts a on a.id ="
                + " tc.account_id where a.email = ?",
            String.class,
            enrolled.email());
    assertThat(stored).doesNotContain(enrolled.secret().replace(" ", ""));
    final List<String> hashes =
        jdbcTemplate.queryForList(
            "select rc.code_hash from totp_recovery_codes rc join accounts a on a.id ="
                + " rc.account_id where a.email = ?",
            String.class,
            enrolled.email());
    assertThat(hashes)
        .hasSize(10)
        .allMatch(hash -> hash.matches("[0-9a-f]{64}"))
        .doesNotContainAnyElementsOf(enrolled.recoveryCodes());
  }

  // ---- setup helpers --------------------------------------------------------------------------

  private String platformToken() throws IOException, InterruptedException {
    return PlatformApiTestSupport.platformAccessToken(
        apiClient, objectMapper, baseUri("/oauth2/token"), SCOPES);
  }

  private UUID registerAPlatformAccount() {
    return PlatformApiTestSupport.registerAPlatformAccount(platformAccounts);
  }

  private UUID createOrganization(final String platformToken, final String name)
      throws IOException, InterruptedException {
    final HttpResponse<String> response =
        apiClient.send(
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
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return UUID.fromString(objectMapper.readTree(response.body()).get("id").asString());
  }

  private UUID createProductionOrganization(final String platformToken, final String name)
      throws IOException, InterruptedException {
    final UUID development = createOrganization(platformToken, name + " (dev)");
    final HttpResponse<String> response =
        apiClient.send(
            HttpRequest.newBuilder(
                    baseUri(
                        "/api/v1/admin/organizations/"
                            + development
                            + ":create-production-environment"))
                .header("Authorization", "Bearer " + platformToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"" + name + "\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    return UUID.fromString(objectMapper.readTree(response.body()).get("id").asString());
  }

  private void setTotpEnabled(
      final String platformToken, final UUID organizationId, final boolean enabled)
      throws IOException, InterruptedException {
    final HttpResponse<String> response =
        apiClient.send(
            HttpRequest.newBuilder(
                    baseUri(
                        "/api/v1/admin/organizations/" + organizationId + "/authentication-policy"))
                .header("Authorization", "Bearer " + platformToken)
                .header("Content-Type", "application/json")
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        """
                        {
                          "emailVerificationRequiredAtSignIn": false,
                          "emailVerificationMethod": "LINK",
                          "emailCodeSignInEnabled": false,
                          "emailLinkSignInEnabled": false,
                          "usernameRequired": false,
                          "usernameSignInEnabled": false,
                          "passwordAtSignUpEnabled": true,
                          "deviceTrustEnabled": false,
                          "selfRegistrationRequiresApproval": false,
                          "totpEnabled": %s
                        }
                        """
                            .formatted(enabled)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
  }

  private void register(final UUID organizationId, final String email) throws Exception {
    final Browser browser = new Browser();
    final HttpResponse<String> response =
        browser.post(
            "/o/" + organizationId + "/register",
            "/o/" + organizationId + "/register",
            "&email="
                + enc(email)
                + "&password="
                + enc(PASSWORD)
                + "&confirmPassword="
                + enc(PASSWORD));
    assertThat(response.statusCode()).as("registration must succeed").isEqualTo(302);
  }

  private URI baseUri(final String path) {
    return URI.create("http://localhost:" + port + path);
  }
}
