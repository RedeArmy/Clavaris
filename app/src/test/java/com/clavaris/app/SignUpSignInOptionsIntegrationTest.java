package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * ADR-0024: the new sign-up/sign-in strategies (username sign-in, passwordless email-code sign-in,
 * device trust step-up), driven end to end through real HTTP requests against a real running app —
 * same "confirmed live, not assumed" bar {@link AuthorizationCodeFlowIntegrationTest} already set
 * for the password-based flow, reusing that class's own bootstrap-token/create-organization
 * machinery rather than duplicating it under a different shape.
 *
 * <p>Deliberately does not re-prove the base password+email flow ({@link
 * AuthorizationCodeFlowIntegrationTest} already does) or the magic-link variant (structurally
 * identical to the code variant proven here, save for the token shape) — proportionate coverage of
 * what's genuinely new, not exhaustive re-coverage of what already has its own test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret"
    })
class SignUpSignInOptionsIntegrationTest extends RedisBackedIntegrationTest {

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
    // The mock bean is a single Spring-managed singleton shared across every test method in this
    // class — without this, a later test's own captor could pick up an earlier test's invocation.
    clearInvocations(mailSender);
  }

  // ADR-0032: the one sign-in field takes a username or an email. A new Organization starts with
  // username sign-in on, so no policy is set here at all.
  @Test
  void aNewOrganizationSignsInWithEitherTheUsernameOrTheEmailAtTheOneField() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Username Sign-In Co");
    registerAccountWithUsername(
        organizationId, "username-flow@example.com", "a-correct-password", "flowuser");

    for (final String identifier : new String[] {"flowuser", "username-flow@example.com"}) {
      String csrfToken = fetchCsrfToken("/o/" + organizationId + "/login");
      HttpResponse<Void> response =
          submitForm(
              "/o/" + organizationId + "/login",
              "_csrf="
                  + csrfToken
                  + "&email="
                  + URLEncoder.encode(identifier, StandardCharsets.UTF_8)
                  + "&password="
                  + URLEncoder.encode("a-correct-password", StandardCharsets.UTF_8));

      assertThat(response.statusCode()).as("signing in with " + identifier).isEqualTo(302);
      assertThat(response.headers().firstValue("Location").orElseThrow())
          .as("a successful sign-in redirects to the ordinary post-login fallback")
          .contains("/login?authenticated");
    }
    assertThat(currentSessionId()).as("a real authenticated session must now exist").isNotBlank();
  }

  // ADR-0032: where the tenant turned username sign-in off, the field is email-only again and a
  // username is refused exactly like a wrong password. This body still carries
  // usernameSignUpEnabled, the switch that was removed (ADR-0031), the way a client written before
  // the removal would: it must be accepted and ignored.
  @Test
  void aTenantThatTurnedUsernameSignInOffAcceptsTheEmailButNotTheUsername() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Email Only Sign-In Co");
    setAuthenticationPolicy(
        platformToken,
        organizationId,
        """
        {
          "emailVerificationRequiredAtSignIn": false,
          "emailVerificationMethod": "LINK",
          "emailCodeSignInEnabled": false,
          "emailLinkSignInEnabled": false,
          "usernameSignUpEnabled": true,
          "usernameRequired": false,
          "usernameSignInEnabled": false,
          "passwordAtSignUpEnabled": true,
          "deviceTrustEnabled": false,
          "selfRegistrationRequiresApproval": false
        }
        """);
    registerAccountWithUsername(
        organizationId, "email-only@example.com", "a-correct-password", "emailonly");

    String loginPage = "/o/" + organizationId + "/login";
    HttpResponse<Void> byUsername =
        submitForm(
            loginPage,
            "_csrf="
                + fetchCsrfToken(loginPage)
                + "&email=emailonly&password="
                + URLEncoder.encode("a-correct-password", StandardCharsets.UTF_8));
    assertThat(byUsername.statusCode())
        .as("the username is refused: the form is re-rendered, not redirected")
        .isEqualTo(200);

    HttpResponse<Void> byEmail =
        submitForm(
            loginPage,
            "_csrf="
                + fetchCsrfToken(loginPage)
                + "&email=email-only%40example.com&password="
                + URLEncoder.encode("a-correct-password", StandardCharsets.UTF_8));
    assertThat(byEmail.statusCode()).isEqualTo(302);
  }

  @Test
  void emailCodeSignInEstablishesARealSessionEndToEnd() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createProductionOrganization(platformToken, "Email Code Sign-In Co");
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
    String email = "email-code-flow@example.com";
    registerAccountWithUsername(organizationId, email, "a-correct-password", null);
    clearInvocations(mailSender); // ignore the registration's own verification-email send

    String requestCsrfToken = fetchCsrfToken("/o/" + organizationId + "/login/email-code");
    HttpResponse<Void> requestResponse =
        submitForm(
            "/o/" + organizationId + "/login/email-code",
            "_csrf="
                + requestCsrfToken
                + "&email="
                + URLEncoder.encode(email, StandardCharsets.UTF_8));
    assertThat(requestResponse.statusCode()).isEqualTo(302);

    // The mail is sent on a background thread now (see RequestEmailSignInCodeService), so wait.
    ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
    verify(mailSender, timeout(5000))
        .sendEmailSignInCode(
            eq(email), eq(new OrganizationId(organizationId)), codeCaptor.capture());
    String rawCode = codeCaptor.getValue();

    String confirmCsrfToken =
        fetchCsrfToken("/o/" + organizationId + "/login/email-code/confirm?email=" + email);
    HttpResponse<Void> confirmResponse =
        submitForm(
            "/o/" + organizationId + "/login/email-code/confirm",
            "_csrf="
                + confirmCsrfToken
                + "&email="
                + URLEncoder.encode(email, StandardCharsets.UTF_8)
                + "&code="
                + rawCode);

    assertThat(confirmResponse.statusCode()).isEqualTo(302);
    assertThat(confirmResponse.headers().firstValue("Location").orElseThrow())
        .contains("/login?authenticated");
    assertThat(currentSessionId()).isNotBlank();
  }

  // ---- sign-in code hardening --------------------------------------------------------------

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

  // The defect this guards: consuming the code with consume() + save() let two confirms that read
  // it
  // while it was still active both sign in. Each request here has its own browser session (and so
  // its own CSRF token), so the only thing that can make the losers fail is the code being spent.
  @Test
  void aSignInCodeSignsInExactlyOnceEvenWhenConfirmedConcurrently() throws Exception {
    String email = "code-race@example.com";
    UUID organizationId = organizationWithEmailCodeSignIn("Code Race Co", email);
    requestCode(organizationId, email);
    String code = nextCodeSentTo(organizationId, email, 1);

    int attempts = 8;
    List<HttpClient> browsers = new ArrayList<>();
    List<String> tokens = new ArrayList<>();
    for (int i = 0; i < attempts; i++) {
      HttpClient browser =
          HttpClient.newBuilder()
              .cookieHandler(new CookieManager())
              .followRedirects(HttpClient.Redirect.NEVER)
              .build();
      HttpResponse<String> page =
          browser.send(
              HttpRequest.newBuilder(
                      baseUri("/o/" + organizationId + "/login/email-code/confirm?email=" + email))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      Matcher matcher = CSRF_TOKEN_PATTERN.matcher(page.body());
      assertThat(matcher.find()).isTrue();
      browsers.add(browser);
      tokens.add(matcher.group(1));
    }

    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(attempts);
    List<Future<HttpResponse<Void>>> results = new ArrayList<>();
    for (int i = 0; i < attempts; i++) {
      HttpClient browser = browsers.get(i);
      String token = tokens.get(i);
      results.add(
          pool.submit(
              () -> {
                go.await();
                return browser.send(
                    HttpRequest.newBuilder(
                            baseUri("/o/" + organizationId + "/login/email-code/confirm"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "_csrf="
                                    + token
                                    + "&email="
                                    + URLEncoder.encode(email, StandardCharsets.UTF_8)
                                    + "&code="
                                    + code))
                        .build(),
                    HttpResponse.BodyHandlers.discarding());
              }));
    }
    go.countDown();
    int signedIn = 0;
    for (Future<HttpResponse<Void>> result : results) {
      HttpResponse<Void> response = result.get(30, TimeUnit.SECONDS);
      if (response.statusCode() == 302
          && response
              .headers()
              .firstValue("Location")
              .orElse("")
              .contains("/login?authenticated")) {
        signedIn++;
      }
    }
    pool.shutdownNow();

    assertThat(signedIn).as("a code is valid only once, even raced").isEqualTo(1);
  }

  // Moves every sign-in code of the account back in time, as if the wait had passed, so the test
  // does not sit through 30 seconds.
  private void letTheResendWaitPass(final String email) {
    jdbcTemplate.update(
        "update verification_tokens set expires_at = expires_at - interval '60 seconds'"
            + " where account_id = (select id from accounts where email = ?)",
        email);
  }

  // ADR-0034: the code that actually arrives is six characters from the 32-symbol alphabet (no 0,
  // 1,
  // O or I), and people type it in lower case with a space or a hyphen: all of that must sign in.
  @Test
  void theCodeThatArrivesIsInTheNewFormatAndSignsInWhenTypedInLowerCaseWithAHyphen()
      throws Exception {
    String email = "code-format@example.com";
    UUID organizationId = organizationWithEmailCodeSignIn("Code Format Co", email);
    requestCode(organizationId, email);
    String code = nextCodeSentTo(organizationId, email, 1);

    assertThat(code).matches("[2-9A-HJ-NP-Z]{6}");

    String typed =
        (code.substring(0, 3) + "-" + code.substring(3)).toLowerCase(java.util.Locale.ROOT);
    HttpResponse<Void> response = confirmCode(organizationId, email, typed);

    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(response.headers().firstValue("Location").orElseThrow())
        .contains("/login?authenticated");
  }

  // Asking for a new code must end the earlier one: several live at once multiply a guesser's
  // chances. The first code is read before the second is requested, so the order is deterministic.
  @Test
  void requestingANewCodeEndsTheEarlierOne() throws Exception {
    String email = "code-reissue@example.com";
    UUID organizationId = organizationWithEmailCodeSignIn("Code Reissue Co", email);
    requestCode(organizationId, email);
    String first = nextCodeSentTo(organizationId, email, 1);
    letTheResendWaitPass(email);
    requestCode(organizationId, email);
    String second = nextCodeSentTo(organizationId, email, 2);

    HttpResponse<Void> withFirst = confirmCode(organizationId, email, first);
    assertThat(withFirst.statusCode())
        .as("the earlier code no longer signs in: the form is shown again, not a redirect")
        .isEqualTo(200);

    HttpResponse<Void> withSecond = confirmCode(organizationId, email, second);
    assertThat(withSecond.statusCode()).isEqualTo(302);
    assertThat(withSecond.headers().firstValue("Location").orElseThrow())
        .contains("/login?authenticated");
  }

  // Asking again inside the 30 seconds sends nothing new and leaves the first code valid: the
  // person still has the email they already got.
  @Test
  void askingAgainWithinTheWaitSendsNothingNewAndKeepsTheFirstCodeValid() throws Exception {
    String email = "code-cooldown@example.com";
    UUID organizationId = organizationWithEmailCodeSignIn("Code Cooldown Co", email);
    requestCode(organizationId, email);
    String first = nextCodeSentTo(organizationId, email, 1);

    requestCode(organizationId, email); // inside the wait: same redirect, no new mail

    verify(mailSender, after(1000).times(1))
        .sendEmailSignInCode(eq(email), eq(new OrganizationId(organizationId)), any());
    HttpResponse<Void> confirm = confirmCode(organizationId, email, first);
    assertThat(confirm.statusCode()).isEqualTo(302);
    assertThat(confirm.headers().firstValue("Location").orElseThrow())
        .contains("/login?authenticated");
  }

  // Asking for a code sends a real email, so it is limited per address; the next one past the limit
  // is a 429. 5 per ten minutes is the configured default.
  @Test
  void requestingSignInCodesIsLimitedPerAddress() throws Exception {
    String email = "code-flood@example.com";
    UUID organizationId = organizationWithEmailCodeSignIn("Code Flood Co", email);
    String page = "/o/" + organizationId + "/login/email-code";

    for (int attempt = 1; attempt <= 5; attempt++) {
      assertThat(
              submitForm(
                      page,
                      "_csrf="
                          + fetchCsrfToken(page)
                          + "&email="
                          + URLEncoder.encode(email, StandardCharsets.UTF_8))
                  .statusCode())
          .as("request %d is within the limit", attempt)
          .isEqualTo(302);
    }
    HttpResponse<Void> overLimit =
        submitForm(
            page,
            "_csrf="
                + fetchCsrfToken(page)
                + "&email="
                + URLEncoder.encode(email, StandardCharsets.UTF_8));

    assertThat(overLimit.statusCode()).isEqualTo(429);
    // Only the first sent a mail: the rest were inside the wait. The 429 sent none either.
    verify(mailSender, timeout(5000).times(1))
        .sendEmailSignInCode(eq(email), eq(new OrganizationId(organizationId)), any());
    verify(mailSender, after(1000).times(1))
        .sendEmailSignInCode(eq(email), eq(new OrganizationId(organizationId)), any());
  }

  // What the caller sees must not say whether the address has an account.
  @Test
  void aCodeRequestLooksTheSameForAKnownAndAnUnknownAddress() throws Exception {
    String known = "code-known@example.com";
    UUID organizationId = organizationWithEmailCodeSignIn("Code Same Co", known);
    String page = "/o/" + organizationId + "/login/email-code";

    HttpResponse<Void> forKnown =
        submitForm(
            page,
            "_csrf="
                + fetchCsrfToken(page)
                + "&email="
                + URLEncoder.encode(known, StandardCharsets.UTF_8));
    HttpResponse<Void> forUnknown =
        submitForm(
            page,
            "_csrf="
                + fetchCsrfToken(page)
                + "&email="
                + URLEncoder.encode("nobody-here@example.com", StandardCharsets.UTF_8));

    assertThat(forUnknown.statusCode()).isEqualTo(forKnown.statusCode()).isEqualTo(302);
    assertThat(forUnknown.headers().firstValue("Location").orElseThrow())
        .contains("/login/email-code/confirm");
    verify(mailSender, timeout(5000))
        .sendEmailSignInCode(eq(known), eq(new OrganizationId(organizationId)), any());
    verify(mailSender, never()).sendEmailSignInCode(eq("nobody-here@example.com"), any(), any());
  }

  private HttpResponse<Void> forgotPassword(final UUID organizationId, final String email)
      throws Exception {
    String page = "/o/" + organizationId + "/forgot-password";
    return submitForm(
        page,
        "_csrf="
            + fetchCsrfToken(page)
            + "&email="
            + URLEncoder.encode(email, StandardCharsets.UTF_8));
  }

  @Test
  void forgotPasswordLooksTheSameForAKnownAndAnUnknownAddress() throws Exception {
    String known = "reset-known@example.com";
    UUID organizationId = organizationWithEmailCodeSignIn("Reset Same Co", known);

    HttpResponse<Void> forKnown = forgotPassword(organizationId, known);
    HttpResponse<Void> forUnknown = forgotPassword(organizationId, "reset-nobody@example.com");

    assertThat(forUnknown.statusCode()).isEqualTo(forKnown.statusCode()).isEqualTo(302);
    assertThat(forUnknown.headers().firstValue("Location").orElseThrow())
        .isEqualTo(forKnown.headers().firstValue("Location").orElseThrow());
    verify(mailSender, timeout(5000))
        .sendPasswordReset(eq(known), eq(new OrganizationId(organizationId)), any());
    verify(mailSender, never()).sendPasswordReset(eq("reset-nobody@example.com"), any(), any());
  }

  @Test
  void forgotPasswordIsLimitedPerAddressAndAskingAgainWithinTheWaitSendsNothingNew()
      throws Exception {
    String email = "reset-flood@example.com";
    UUID organizationId = organizationWithEmailCodeSignIn("Reset Flood Co", email);

    for (int attempt = 1; attempt <= 5; attempt++) {
      assertThat(forgotPassword(organizationId, email).statusCode())
          .as("request %d is within the limit", attempt)
          .isEqualTo(302);
    }
    assertThat(forgotPassword(organizationId, email).statusCode()).isEqualTo(429);

    verify(mailSender, timeout(5000).times(1))
        .sendPasswordReset(eq(email), eq(new OrganizationId(organizationId)), any());
    verify(mailSender, after(1000).times(1))
        .sendPasswordReset(eq(email), eq(new OrganizationId(organizationId)), any());
  }

  @Test
  void deviceTrustBlocksAnUnrecognizedDeviceAndUnblocksAfterTheCorrectCode() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createProductionOrganization(platformToken, "Device Trust Co");
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
    String email = "device-trust-flow@example.com";
    registerAccountWithUsername(organizationId, email, "a-correct-password", null);
    clearInvocations(mailSender);

    // First login ever from this browser — no DeviceCookie presented yet, so this must be paused
    // for a step-up challenge rather than establishing the session directly.
    String loginCsrfToken = fetchCsrfToken("/o/" + organizationId + "/login");
    HttpResponse<Void> firstLoginAttempt =
        submitForm(
            "/o/" + organizationId + "/login",
            "_csrf="
                + loginCsrfToken
                + "&email="
                + URLEncoder.encode(email, StandardCharsets.UTF_8)
                + "&password=a-correct-password");
    assertThat(firstLoginAttempt.statusCode()).isEqualTo(302);
    assertThat(firstLoginAttempt.headers().firstValue("Location").orElseThrow())
        .as(
            "an unrecognized device on a device-trust-enabled Organization must be paused, not"
                + " signed in directly")
        .endsWith("/o/" + organizationId + "/login/device-trust");

    ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
    verify(mailSender)
        .sendDeviceTrustChallengeCode(
            eq(email), eq(new OrganizationId(organizationId)), codeCaptor.capture());
    String rawChallengeCode = codeCaptor.getValue();

    String challengeCsrfToken = fetchCsrfToken("/o/" + organizationId + "/login/device-trust");
    HttpResponse<Void> challengeResponse =
        submitForm(
            "/o/" + organizationId + "/login/device-trust",
            "_csrf=" + challengeCsrfToken + "&code=" + rawChallengeCode);

    assertThat(challengeResponse.statusCode()).isEqualTo(302);
    assertThat(challengeResponse.headers().firstValue("Location").orElseThrow())
        .contains("/login?authenticated");
    String sessionAfterChallenge = currentSessionId();
    assertThat(sessionAfterChallenge).isNotBlank();
    assertThat(deviceCookie(organizationId))
        .as("a real DeviceCookie must now be set for this Organization")
        .isPresent();

    // Second login, same browser (same CookieManager, so the same DeviceCookie is presented this
    // time) — must be recognized and go straight through, never redirected to the challenge again.
    // Captured before clearing the store: mirrors a real browser starting a brand new (logged-out)
    // session while still holding on to its own long-lived device cookie.
    HttpCookie savedDeviceCookie = deviceCookie(organizationId).orElseThrow();
    cookieManager.getCookieStore().removeAll();
    cookieManager.getCookieStore().add(URI.create("http://localhost:" + port), savedDeviceCookie);

    String secondLoginCsrfToken = fetchCsrfToken("/o/" + organizationId + "/login");
    HttpResponse<Void> secondLoginAttempt =
        submitForm(
            "/o/" + organizationId + "/login",
            "_csrf="
                + secondLoginCsrfToken
                + "&email="
                + URLEncoder.encode(email, StandardCharsets.UTF_8)
                + "&password=a-correct-password");

    assertThat(secondLoginAttempt.statusCode()).isEqualTo(302);
    assertThat(secondLoginAttempt.headers().firstValue("Location").orElseThrow())
        .as("a recognized device must never be challenged again")
        .contains("/login?authenticated");
  }

  private Optional<HttpCookie> deviceCookie(UUID organizationId) {
    String cookieName = "clavaris_device_" + organizationId;
    return cookieManager.getCookieStore().getCookies().stream()
        .filter(cookie -> cookieName.equals(cookie.getName()))
        .findFirst();
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

  // BR-ORG-08: POST /api/v1/admin/organizations always creates a DEVELOPMENT Organization — BR-ID-
  // 15 means that tier never triggers a real outbound send (the VerificationToken is still really
  // created, only the mail call is skipped), which this suite's own mailSender-capture assertions
  // depend on. Promoting to the paired PRODUCTION sibling (a real, separate Organization row) is
  // what real mail delivery actually requires — used only by the tests that need to capture a real
  // sent code, not by every test in this class.
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

  // Same "write the row directly, never log in as it" shape as AuthorizationCodeFlowIntegrationTest
  // — this suite never authenticates as the platform tier, only needs a real owner row to exist.
  private UUID registerAPlatformAccount() {
    PlatformAccount account =
        PlatformAccount.register(new Email("owner-" + UUID.randomUUID() + "@example.test"));
    account.attachPasswordCredential("not-a-real-hash-this-test-never-logs-in");
    platformAccounts.save(account);
    return account.id().value();
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

  // Same "SESSION", not "JSESSIONID" rationale as AuthorizationCodeFlowIntegrationTest's own
  // identical helper (TD-ARCH-002: Spring Session, not the servlet container's own tracking), and
  // the same per-tenant "SESSION_<organizationId>" name (each test signs in to one Organization).
  private String currentSessionId() {
    return cookieManager.getCookieStore().getCookies().stream()
        .filter(cookie -> cookie.getName().matches("(?i)SESSION(_[0-9a-f]{32})?"))
        .map(HttpCookie::getValue)
        .findFirst()
        .orElse(null);
  }
}
