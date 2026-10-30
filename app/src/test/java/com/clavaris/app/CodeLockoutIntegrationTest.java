package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.timeout;
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
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
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
import tools.jackson.databind.ObjectMapper;

/**
 * ADR-0033: too many wrong one-time codes in a row pause the method, for a real address and an
 * unknown one alike, and tell the owner. The limit is lowered to 3 here (the default is 30), and
 * this is its own class because that limit would also pause the 8 racing confirmations of the
 * single-use test in {@code SignUpSignInOptionsIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret",
      // ADR-0033: a low limit, so a few wrong codes are enough to lock (the default is 30).
      "clavaris.code-lockout.max-consecutive-failures=3"
    })
class CodeLockoutIntegrationTest extends RedisBackedIntegrationTest {

  private static final Pattern CSRF_TOKEN_PATTERN =
      Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Autowired private PlatformAccountRepository platformAccounts;
  @Autowired private MailSender mailSender;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final CookieManager cookieManager = new CookieManager();
  private final HttpClient httpClient =
      HttpClient.newBuilder()
          .cookieHandler(cookieManager)
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void resetMailSenderInvocations() {
    clearInvocations(mailSender);
  }

  private String confirmCodeBody(final UUID organizationId, final String email, final String code)
      throws Exception {
    String page = "/o/" + organizationId + "/login/email-code/confirm?email=" + email;
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/login/email-code/confirm"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "_csrf="
                        + fetchCsrfToken(page)
                        + "&email="
                        + URLEncoder.encode(email, StandardCharsets.UTF_8)
                        + "&code="
                        + code))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as("a refused code re-renders the form").isEqualTo(200);
    return response.body();
  }

  private static final String LOCKED_MESSAGE = "Too many incorrect codes";
  private static final String WRONG_MESSAGE = "That code is invalid or has expired";

  // Three wrong codes in a row (the limit this class sets) pause the method, and the pause refuses
  // even the right code. The owner is told, once, how long.
  @Test
  void tooManyWrongCodesPauseTheMethodEvenForTheRightCodeAndTheOwnerIsToldOnce() throws Exception {
    String email = "lock-owner@example.com";
    UUID organizationId = organizationWithEmailCodeSignIn("Lock Owner Co", email);
    requestCode(organizationId, email);
    String rightCode = nextCodeSentTo(organizationId, email, 1);

    for (int attempt = 1; attempt <= 3; attempt++) {
      assertThat(confirmCodeBody(organizationId, email, "ZZZZZZ"))
          .as("wrong code %d", attempt)
          .contains(WRONG_MESSAGE)
          .doesNotContain(LOCKED_MESSAGE);
    }

    assertThat(confirmCodeBody(organizationId, email, rightCode))
        .as("the right code is refused while the method is paused")
        .contains(LOCKED_MESSAGE);
    verify(mailSender, timeout(5000).times(1))
        .sendCodeLockedNotice(
            eq(email), eq(new OrganizationId(organizationId)), eq(Duration.ofMinutes(15)));
    verify(mailSender, after(1000).times(1))
        .sendCodeLockedNotice(eq(email), eq(new OrganizationId(organizationId)), any());
  }

  // An address with no account is paused exactly like a real one, so the lock is not a way to find
  // out which addresses have an account. Only there is nobody to mail.
  @Test
  void anAddressWithNoAccountIsPausedTheSameAndNobodyIsMailed() throws Exception {
    UUID organizationId =
        organizationWithEmailCodeSignIn("Lock Unknown Co", "lock-real@example.com");
    String ghost = "lock-nobody@example.com";

    for (int attempt = 1; attempt <= 3; attempt++) {
      assertThat(confirmCodeBody(organizationId, ghost, "ZZZZZZ")).contains(WRONG_MESSAGE);
    }

    assertThat(confirmCodeBody(organizationId, ghost, "ZZZZZZ")).contains(LOCKED_MESSAGE);
    verify(mailSender, after(1000).never()).sendCodeLockedNotice(eq(ghost), any(), any());
  }

  // Failures do not pile up across a success: two wrong, one right, then two wrong is not five in
  // a row, so the method is still open.
  @Test
  void aRightCodeClearsTheCountOfWrongOnes() throws Exception {
    String email = "lock-clear@example.com";
    UUID organizationId = organizationWithEmailCodeSignIn("Lock Clear Co", email);
    requestCode(organizationId, email);
    String rightCode = nextCodeSentTo(organizationId, email, 1);
    confirmCodeBody(organizationId, email, "ZZZZZZ");
    confirmCodeBody(organizationId, email, "YYYYYY");

    HttpResponse<Void> signedIn = confirmCode(organizationId, email, rightCode);
    assertThat(signedIn.statusCode()).isEqualTo(302);
    assertThat(signedIn.headers().firstValue("Location").orElseThrow())
        .contains("/login?authenticated");

    assertThat(confirmCodeBody(organizationId, email, "XXXXXX")).contains(WRONG_MESSAGE);
    assertThat(confirmCodeBody(organizationId, email, "WWWWWW"))
        .as("only two wrong since the success, so still not paused")
        .doesNotContain(LOCKED_MESSAGE);
  }

  // While the method is paused a code could not be redeemed, so none is sent: it would only mail
  // the
  // owner a secret they cannot use.
  @Test
  void askingForACodeWhilePausedSendsNothing() throws Exception {
    String email = "lock-request@example.com";
    UUID organizationId = organizationWithEmailCodeSignIn("Lock Request Co", email);
    requestCode(organizationId, email);
    nextCodeSentTo(organizationId, email, 1);
    for (int attempt = 1; attempt <= 3; attempt++) {
      confirmCodeBody(organizationId, email, "ZZZZZZ");
    }
    letTheResendWaitPass(email);

    requestCode(organizationId, email); // same redirect as ever

    verify(mailSender, after(1000).times(1))
        .sendEmailSignInCode(eq(email), eq(new OrganizationId(organizationId)), any());
  }

  // The device-verification code had no limit on attempts at all; it is paused the same way.
  @Test
  void tooManyWrongDeviceCodesPauseTheVerificationAndTheOwnerIsTold() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createProductionOrganization(platformToken, "Lock Device Co");
    setAuthenticationPolicy(
        platformToken,
        organizationId,
        """
        {
          "emailVerificationRequiredAtSignIn": false,
          "emailVerificationMethod": "LINK",
          "emailCodeSignInEnabled": false,
          "emailLinkSignInEnabled": false,
          "usernameRequired": false,
          "usernameSignInEnabled": false,
          "passwordAtSignUpEnabled": true,
          "deviceTrustEnabled": true,
          "selfRegistrationRequiresApproval": false
        }
        """);
    String email = "lock-device@example.com";
    registerAccountWithUsername(organizationId, email, "a-correct-password", null);
    clearInvocations(mailSender);
    HttpResponse<Void> login =
        submitForm(
            "/o/" + organizationId + "/login",
            "_csrf="
                + fetchCsrfToken("/o/" + organizationId + "/login")
                + "&email="
                + URLEncoder.encode(email, StandardCharsets.UTF_8)
                + "&password=a-correct-password");
    assertThat(login.headers().firstValue("Location").orElseThrow())
        .endsWith("/o/" + organizationId + "/login/device-trust");
    ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
    verify(mailSender, timeout(5000))
        .sendDeviceTrustChallengeCode(
            eq(email), eq(new OrganizationId(organizationId)), codeCaptor.capture());
    String rightCode = codeCaptor.getValue();

    for (int attempt = 1; attempt <= 3; attempt++) {
      assertThat(deviceChallengeBody(organizationId, "ZZZZZZ"))
          .as("wrong device code %d", attempt)
          .contains(WRONG_MESSAGE);
    }

    assertThat(deviceChallengeBody(organizationId, rightCode)).contains(LOCKED_MESSAGE);
    verify(mailSender, timeout(5000))
        .sendCodeLockedNotice(
            eq(email), eq(new OrganizationId(organizationId)), eq(Duration.ofMinutes(15)));
  }

  private String deviceChallengeBody(final UUID organizationId, final String code)
      throws Exception {
    String page = "/o/" + organizationId + "/login/device-trust";
    HttpRequest request =
        HttpRequest.newBuilder(baseUri(page))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "_csrf=" + fetchCsrfToken(page) + "&code=" + code))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return response.body();
  }

  // Moves every sign-in code of the account back in time, as if the resend wait had passed.
  private void letTheResendWaitPass(final String email) {
    jdbcTemplate.update(
        "update verification_tokens set expires_at = expires_at - interval '60 seconds'"
            + " where account_id = (select id from accounts where email = ?)",
        email);
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
                    "grant_type=client_credentials&scope="
                        + URLEncoder.encode(
                            "platform:organizations:write"
                                + " platform:account-authentication-policy:write",
                            StandardCharsets.UTF_8)))
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

  private UUID createProductionOrganization(String platformToken, String name)
      throws IOException, InterruptedException {
    UUID developmentOrganizationId = createOrganization(platformToken, name + " (dev)");
    HttpRequest request =
        HttpRequest.newBuilder(
                baseUri(
                    "/api/v1/admin/organizations/"
                        + developmentOrganizationId
                        + ":create-production-environment"))
            .header("Authorization", "Bearer " + platformToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"" + name + "\"}"))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode())
        .as("promoting to a PRODUCTION environment must succeed: " + response.body())
        .isEqualTo(201);
    return UUID.fromString(objectMapper.readTree(response.body()).get("id").asString());
  }

  private void setAuthenticationPolicy(
      String platformToken, UUID organizationId, String requestBody)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(
                baseUri("/api/v1/admin/organizations/" + organizationId + "/authentication-policy"))
            .header("Authorization", "Bearer " + platformToken)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode())
        .as("setting the authentication policy must succeed: " + response.body())
        .isEqualTo(200);
  }

  private void registerAccountWithUsername(
      UUID organizationId, String email, String password, String username)
      throws IOException, InterruptedException {
    String csrfToken = fetchCsrfToken("/o/" + organizationId + "/register");
    StringBuilder body =
        new StringBuilder("_csrf=")
            .append(csrfToken)
            .append("&email=")
            .append(URLEncoder.encode(email, StandardCharsets.UTF_8))
            .append("&password=")
            .append(URLEncoder.encode(password, StandardCharsets.UTF_8))
            .append("&confirmPassword=")
            .append(URLEncoder.encode(password, StandardCharsets.UTF_8));
    if (username != null) {
      body.append("&username=").append(URLEncoder.encode(username, StandardCharsets.UTF_8));
    }
    HttpResponse<Void> response = submitForm("/o/" + organizationId + "/register", body.toString());
    assertThat(response.statusCode())
        .as("registration must succeed for this test's own setup to be valid")
        .isEqualTo(302);
  }

  private String fetchCsrfToken(String path) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(baseUri(path)).GET().build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    Matcher matcher = CSRF_TOKEN_PATTERN.matcher(response.body());
    assertThat(matcher.find()).as(path + " must render a _csrf hidden input").isTrue();
    return matcher.group(1);
  }

  private HttpResponse<Void> submitForm(String path, String formBody)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUri(path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(formBody))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.discarding());
  }

  private URI baseUri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private UUID organizationWithEmailCodeSignIn(final String name, final String email)
      throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createProductionOrganization(platformToken, name);
    setAuthenticationPolicy(
        platformToken,
        organizationId,
        """
        {
          "emailVerificationRequiredAtSignIn": false,
          "emailVerificationMethod": "LINK",
          "emailCodeSignInEnabled": true,
          "emailLinkSignInEnabled": false,
          "usernameRequired": false,
          "usernameSignInEnabled": false,
          "passwordAtSignUpEnabled": true,
          "deviceTrustEnabled": false,
          "selfRegistrationRequiresApproval": false
        }
        """);
    registerAccountWithUsername(organizationId, email, "a-correct-password", null);
    clearInvocations(mailSender);
    return organizationId;
  }

  private void requestCode(final UUID organizationId, final String email) throws Exception {
    HttpResponse<Void> response =
        submitForm(
            "/o/" + organizationId + "/login/email-code",
            "_csrf="
                + fetchCsrfToken("/o/" + organizationId + "/login/email-code")
                + "&email="
                + URLEncoder.encode(email, StandardCharsets.UTF_8));
    assertThat(response.statusCode()).as("requesting a code").isEqualTo(302);
  }

  private String nextCodeSentTo(final UUID organizationId, final String email, final int nth) {
    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(mailSender, timeout(5000).times(nth))
        .sendEmailSignInCode(eq(email), eq(new OrganizationId(organizationId)), captor.capture());
    return captor.getAllValues().get(nth - 1);
  }

  private HttpResponse<Void> confirmCode(
      final UUID organizationId, final String email, final String code) throws Exception {
    return submitForm(
        "/o/" + organizationId + "/login/email-code/confirm",
        "_csrf="
            + fetchCsrfToken("/o/" + organizationId + "/login/email-code/confirm?email=" + email)
            + "&email="
            + URLEncoder.encode(email, StandardCharsets.UTF_8)
            + "&code="
            + code);
  }

  private Optional<HttpCookie> deviceCookie(UUID organizationId) {
    String cookieName = "clavaris_device_" + organizationId;
    return cookieManager.getCookieStore().getCookies().stream()
        .filter(cookie -> cookieName.equals(cookie.getName()))
        .findFirst();
  }

  private String currentSessionId() {
    return cookieManager.getCookieStore().getCookies().stream()
        .filter(cookie -> cookie.getName().matches("(?i)SESSION(_[0-9a-f]{32})?"))
        .map(HttpCookie::getValue)
        .findFirst()
        .orElse(null);
  }

  private UUID registerAPlatformAccount() {
    PlatformAccount account =
        PlatformAccount.register(new Email("owner-" + UUID.randomUUID() + "@example.test"));
    account.attachPasswordCredential("not-a-real-hash-this-test-never-logs-in");
    platformAccounts.save(account);
    return account.id().value();
  }
}
