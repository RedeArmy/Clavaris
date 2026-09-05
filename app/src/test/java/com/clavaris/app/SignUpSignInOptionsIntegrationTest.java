package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
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

  @Test
  void usernameSignInEstablishesARealSessionEndToEnd() throws Exception {
    String platformToken = requestPlatformAccessToken();
    UUID organizationId = createOrganization(platformToken, "Username Sign-In Co");
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
          "usernameSignInEnabled": true,
          "passwordAtSignUpEnabled": true,
          "deviceTrustEnabled": false
        }
        """);
    registerAccountWithUsername(
        organizationId, "username-flow@example.com", "a-correct-password", "flowuser");

    String csrfToken = fetchCsrfToken("/o/" + organizationId + "/login/username");
    HttpResponse<Void> response =
        submitForm(
            "/o/" + organizationId + "/login/username",
            "_csrf="
                + csrfToken
                + "&username=flowuser&password="
                + URLEncoder.encode("a-correct-password", StandardCharsets.UTF_8));

    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(response.headers().firstValue("Location").orElseThrow())
        .as("a successful username sign-in redirects to the ordinary post-login fallback")
        .contains("/login?authenticated");
    assertThat(currentSessionId()).as("a real authenticated session must now exist").isNotBlank();
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
          "usernameSignUpEnabled": false,
          "usernameRequired": false,
          "usernameSignInEnabled": false,
          "passwordAtSignUpEnabled": true,
          "deviceTrustEnabled": false
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

    ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
    verify(mailSender)
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
          "usernameSignUpEnabled": false,
          "usernameRequired": false,
          "usernameSignInEnabled": false,
          "passwordAtSignUpEnabled": true,
          "deviceTrustEnabled": true
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
  // identical helper (TD-ARCH-002: Spring Session, not the servlet container's own tracking).
  private String currentSessionId() {
    return cookieManager.getCookieStore().getCookies().stream()
        .filter(cookie -> "SESSION".equalsIgnoreCase(cookie.getName()))
        .map(HttpCookie::getValue)
        .findFirst()
        .orElse(null);
  }
}
