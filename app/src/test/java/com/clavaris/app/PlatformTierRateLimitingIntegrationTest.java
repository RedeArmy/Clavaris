package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.app.support.TestMailSenderConfig;
import com.clavaris.identity.application.usecase.registeraccount.PasswordHasher;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccount;
import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * TD-TEST-003: real, end-to-end proof that the anti-abuse rate-limit filter is actually wired
 * correctly onto the two security chains {@link RateLimitingIntegrationTest} never touches — the
 * platform {@code client_credentials} tier ({@code PlatformAuthorizationServerConfig}) and the
 * whole {@code /platform/**} dashboard tier ({@code PlatformDashboardSecurityConfig}). Before this
 * class, both chains' own rate-limit rules were proven correct only in isolation ({@code
 * AntiAbuseRateLimitingFilterTest}, a mocked {@code RateLimiter} constructing the filter directly)
 * — never that Spring Security actually registers the filter, in the right place, on these two real
 * chains. That gap is exactly the class of bug this feature already shipped once (TD-SEC-001's own
 * closure note: three {@code addFilterAfter} calls anchored at the same shared filter class
 * silently kept only the last-registered one) and only caught because the one chain with
 * integration coverage happened to be the one it broke — these two chains had no such proof until
 * now.
 *
 * <p>Deliberately reuses the real, configured default limits (read via {@code @Value}, not
 * hardcoded) rather than overriding them low — same convention {@link RateLimitingIntegrationTest}
 * already established, and it keeps every rule's own Redis counter starting from a value this class
 * never has to reason about resetting between test methods sharing one Postgres/Redis container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=platform-rate-limit-test-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-platform-rate-limit-test-secret"
    })
// RedisBackedIntegrationTest's own REDIS container field is declared on that abstract class
// itself, so every concrete subclass across the whole test suite shares the exact same running
// Redis — including the "platform-login:ip" counter blocksPlatformLoginWith429AfterThePerIp
// LimitIsExceededAcrossDifferentAccounts below deliberately drives past its limit and leaves
// poisoned for the rest of this window. Explicit ordering, not a JUnit default, guarantees the
// one other test in this class that also needs a working /platform/login (the create-organization
// fixture's own login step) runs before that happens — real shared global state this file cannot
// avoid touching, not a design this test suite would choose voluntarily.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlatformTierRateLimitingIntegrationTest extends RedisBackedIntegrationTest {

  private static final Pattern CSRF_TOKEN_PATTERN =
      Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Value("${clavaris.rate-limit.token.per-client-limit:20}")
  private int tokenPerClientLimit;

  @Value("${clavaris.rate-limit.login.per-account-limit:10}")
  private int loginPerAccountLimit;

  @Value("${clavaris.rate-limit.login.per-ip-limit:30}")
  private int loginPerIpLimit;

  @Value("${clavaris.rate-limit.platform-register.per-ip-limit:10}")
  private int registerPerIpLimit;

  @Value("${clavaris.rate-limit.platform-forgot-password.per-ip-limit:10}")
  private int forgotPasswordPerIpLimit;

  @Value("${clavaris.rate-limit.platform-create-organization.per-account-limit:10}")
  private int createOrganizationPerAccountLimit;

  @Autowired private PlatformAccountRepository platformAccounts;
  @Autowired private PasswordHasher passwordHasher;

  private final HttpClient httpClient =
      HttpClient.newBuilder()
          .cookieHandler(new CookieManager())
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  @Test
  @Order(2)
  void blocksPlatformTokenClientCredentialsWith429AfterThePerClientLimitIsExceeded()
      throws Exception {
    for (int attempt = 1; attempt <= tokenPerClientLimit; attempt++) {
      assertThat(requestPlatformToken().statusCode())
          .as(
              "attempt %d of %d (the configured limit) must be allowed",
              attempt, tokenPerClientLimit)
          .isEqualTo(200);
    }

    HttpResponse<String> overLimit = requestPlatformToken();

    assertThat(overLimit.statusCode()).isEqualTo(429);
    assertThat(overLimit.headers().firstValue("Retry-After")).isPresent();
  }

  @Test
  @Order(3)
  void blocksPlatformLoginWith429AfterThePerAccountLimitIsExceeded() throws Exception {
    String csrfToken = fetchCsrfToken("/platform/login");
    String email = "account-limit-victim@example.com";

    HttpResponse<String> lastAllowed = null;
    for (int attempt = 1; attempt <= loginPerAccountLimit; attempt++) {
      lastAllowed = submitPlatformLogin(csrfToken, email);
    }
    assertThat(lastAllowed.statusCode())
        .as("attempts up to and including the configured account limit must still be allowed")
        .isEqualTo(200);

    HttpResponse<String> overLimit = submitPlatformLogin(csrfToken, email);

    assertThat(overLimit.statusCode())
        .as(
            "blocked by platform-login:account, not the much higher platform-login:ip ceiling "
                + "(this test's own %d attempts stay far under it)",
            loginPerAccountLimit + 1)
        .isEqualTo(429);
  }

  // Deliberately a different email on every attempt — the per-account counter for each one
  // individually never exceeds 1, so ONLY the per-IP rule can possibly produce the 429 this test
  // asserts. No fixed loop bound: the shared loopback IP this whole test class runs from may
  // already carry some budget consumed by blocksPlatformLoginWith429AfterThePerAccountLimit
  // IsExceeded (JUnit doesn't guarantee method order) — a generous safety margin makes this
  // resilient to that instead of assuming a fresh counter.
  @Test
  @Order(6)
  void blocksPlatformLoginWith429AfterThePerIpLimitIsExceededAcrossDifferentAccounts()
      throws Exception {
    String csrfToken = fetchCsrfToken("/platform/login");
    int safetyMargin = loginPerAccountLimit + 5;

    boolean sawTooManyRequests = false;
    for (int attempt = 0;
        attempt < loginPerIpLimit + safetyMargin && !sawTooManyRequests;
        attempt++) {
      HttpResponse<String> response =
          submitPlatformLogin(csrfToken, "ip-limit-" + UUID.randomUUID() + "@example.com");
      sawTooManyRequests = response.statusCode() == 429;
    }

    assertThat(sawTooManyRequests)
        .as(
            "platform-login:ip must eventually block, proven by distinct-account attempts that "
                + "structurally could never trip the per-account rule instead")
        .isTrue();
  }

  @Test
  @Order(4)
  void blocksPlatformRegisterWith429AfterThePerIpLimitIsExceeded() throws Exception {
    String csrfToken = fetchCsrfToken("/platform/register");

    HttpResponse<Void> lastAllowed = null;
    for (int attempt = 1; attempt <= registerPerIpLimit; attempt++) {
      lastAllowed = submitPlatformRegister(csrfToken, "register-limit-" + attempt + "@example.com");
    }
    assertThat(lastAllowed.statusCode()).isEqualTo(302);

    HttpResponse<Void> overLimit =
        submitPlatformRegister(csrfToken, "register-limit-over@example.com");

    assertThat(overLimit.statusCode()).isEqualTo(429);
  }

  @Test
  @Order(5)
  void blocksPlatformForgotPasswordWith429AfterThePerIpLimitIsExceeded() throws Exception {
    String csrfToken = fetchCsrfToken("/platform/forgot-password");

    HttpResponse<Void> lastAllowed = null;
    for (int attempt = 1; attempt <= forgotPasswordPerIpLimit; attempt++) {
      // BR-ID-05 anti-enumeration: the account doesn't need to exist for this endpoint to accept
      // the submission and (separately) rate-limit it — same reasoning RateLimitIdentifiers' own
      // Javadoc gives for keying this rule by IP, never by the submitted email.
      lastAllowed =
          submitPlatformForgotPassword(csrfToken, "forgot-limit-" + attempt + "@example.com");
    }
    assertThat(lastAllowed.statusCode()).isEqualTo(302);

    HttpResponse<Void> overLimit =
        submitPlatformForgotPassword(csrfToken, "forgot-limit-over@example.com");

    assertThat(overLimit.statusCode()).isEqualTo(429);
  }

  // @Order(1): must run before blocksPlatformLoginWith429AfterThePerIpLimitIsExceededAcross
  // DifferentAccounts (see this class's own Javadoc for why) — the only other test here whose
  // fixture setup also needs a working /platform/login.
  @Test
  @Order(1)
  void blocksCreateOrganizationWith429AfterThePerAccountLimitIsExceeded() throws Exception {
    String email = "create-org-limit@example.com";
    registerAndLogInAVerifiedPlatformAccount(email, "the-original-password");
    String dashboardCsrfToken = fetchCsrfToken("/platform/dashboard");

    HttpResponse<Void> lastAllowed = null;
    for (int attempt = 1; attempt <= createOrganizationPerAccountLimit; attempt++) {
      lastAllowed = submitCreateOrganization(dashboardCsrfToken, "Org " + attempt);
    }
    assertThat(lastAllowed.statusCode()).isEqualTo(302);

    HttpResponse<Void> overLimit = submitCreateOrganization(dashboardCsrfToken, "One Org Too Many");

    assertThat(overLimit.statusCode()).isEqualTo(429);
  }

  private HttpResponse<String> requestPlatformToken() throws IOException, InterruptedException {
    String basicAuth =
        Base64.getEncoder()
            .encodeToString(
                "platform-rate-limit-test-client:a-platform-rate-limit-test-secret".getBytes());
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/oauth2/token"))
            .header("Authorization", "Basic " + basicAuth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "grant_type=client_credentials&scope=platform:organizations:write"))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> submitPlatformLogin(String csrfToken, String email)
      throws IOException, InterruptedException {
    String body = "_csrf=" + csrfToken + "&email=" + email + "&password=wrong-password";
    return httpClient.send(
        HttpRequest.newBuilder(baseUri("/platform/login"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<Void> submitPlatformRegister(String csrfToken, String email)
      throws IOException, InterruptedException {
    String body =
        "_csrf="
            + csrfToken
            + "&email="
            + email
            + "&password=a-decent-password"
            + "&confirmPassword=a-decent-password";
    return httpClient.send(
        HttpRequest.newBuilder(baseUri("/platform/register"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.discarding());
  }

  private HttpResponse<Void> submitPlatformForgotPassword(String csrfToken, String email)
      throws IOException, InterruptedException {
    String body = "_csrf=" + csrfToken + "&email=" + email;
    return httpClient.send(
        HttpRequest.newBuilder(baseUri("/platform/forgot-password"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.discarding());
  }

  private HttpResponse<Void> submitCreateOrganization(String csrfToken, String name)
      throws IOException, InterruptedException {
    String body = "_csrf=" + csrfToken + "&name=" + name.replace(" ", "+");
    return httpClient.send(
        HttpRequest.newBuilder(baseUri("/platform/dashboard"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.discarding());
  }

  // Deliberately bypasses POST /platform/register entirely — going through it would consume the
  // same shared platform-register:ip Redis counter blocksPlatformRegisterWith429AfterThePerIp
  // LimitIsExceeded's own test already drives to its limit, and JUnit gives no ordering guarantee
  // between the two. A row written directly through the repository (same "write the fixture
  // directly" pattern OrganizationOidcIssuerIntegrationTest's own registerAPlatformAccount()
  // already uses), pre-verified, with a real Argon2 hash so the real HTTP login step below still
  // exercises genuine password verification, not a bypass of that too.
  private void registerAndLogInAVerifiedPlatformAccount(String email, String password)
      throws IOException, InterruptedException {
    PlatformAccount account = PlatformAccount.register(new Email(email));
    account.attachPasswordCredential(passwordHasher.hash(password));
    account.verifyEmail();
    platformAccounts.save(account);

    String loginCsrfToken = fetchCsrfToken("/platform/login");
    String body = "_csrf=" + loginCsrfToken + "&email=" + email + "&password=" + password;
    HttpResponse<Void> loginResponse =
        httpClient.send(
            HttpRequest.newBuilder(baseUri("/platform/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.discarding());
    if (loginResponse.statusCode() != 302) {
      throw new IllegalStateException(
          "Fixture login failed, expected 302, got " + loginResponse.statusCode());
    }
  }

  private String fetchCsrfToken(String getPath) throws IOException, InterruptedException {
    HttpResponse<String> form =
        httpClient.send(
            HttpRequest.newBuilder(baseUri(getPath)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    Matcher matcher = CSRF_TOKEN_PATTERN.matcher(form.body());
    if (!matcher.find()) {
      throw new IllegalStateException("No CSRF token found on " + getPath);
    }
    return matcher.group(1);
  }

  private URI baseUri(String path) {
    return URI.create("http://localhost:" + port + path);
  }
}
