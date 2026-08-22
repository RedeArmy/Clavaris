package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.clavaris.app.support.TestMailSenderConfig;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.PlatformMailSender;
import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ADR-0012, end to end against real code, real Postgres, real HTTP — the same "confirmed live, not
 * assumed" bar as every other flow in this suite: register a {@code PlatformAccount}, verify its
 * email, log in (a plain authenticated session, not an OAuth token — see {@code
 * PlatformAuthenticatedSessionEstablisher}'s own Javadoc for why), create an Organization from the
 * session-authenticated dashboard, confirm it's really owned by that account in the database, then
 * prove a password reset actually signs the dashboard session out (BR-ID-04's ADR-0012 equivalent,
 * enforced via Spring Security's {@code SessionRegistry}/{@code ConcurrentSessionFilter} — a
 * mocked-mail-sender unit test can prove {@code expireNow()} was called, but only a real HTTP
 * request against a real session can prove the browser is actually rejected afterward).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
class PlatformAccountDashboardIntegrationTest {

  private static final Pattern CSRF_TOKEN_PATTERN =
      Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Autowired private PlatformMailSender mailSender;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final CookieManager cookieManager = new CookieManager();
  private final HttpClient httpClient =
      HttpClient.newBuilder()
          .cookieHandler(cookieManager)
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  @Test
  void registersVerifiesLogsInCreatesAnOrganizationAndPasswordResetSignsOutTheDashboard()
      throws Exception {
    String email = "founder@example.com";
    String originalPassword = "the-original-password";

    registerPlatformAccount(email, originalPassword);

    ArgumentCaptor<String> verificationToken = ArgumentCaptor.forClass(String.class);
    verify(mailSender).sendPlatformAccountEmailVerification(eq(email), verificationToken.capture());
    HttpResponse<String> verifyResponse = getVerifyEmail(verificationToken.getValue());
    assertThat(verifyResponse.statusCode()).isEqualTo(200);
    assertThat(verifyResponse.body()).contains("Email verified");

    assertThat(login(email, originalPassword).statusCode()).isEqualTo(302);

    // The dashboard is reachable now that the session is authenticated, and creating an
    // Organization from it actually persists one owned by this exact PlatformAccount.
    HttpResponse<String> dashboardBeforeCreate = getDashboard();
    assertThat(dashboardBeforeCreate.statusCode()).isEqualTo(200);
    assertThat(dashboardBeforeCreate.body()).contains("don't have any organizations yet");

    HttpResponse<Void> createResponse = createOrganization("Kiniela");
    assertThat(createResponse.statusCode()).isEqualTo(302);

    HttpResponse<String> dashboardAfterCreate = getDashboard();
    assertThat(dashboardAfterCreate.body()).contains("Kiniela");

    Integer ownedOrganizations =
        jdbcTemplate.queryForObject(
            "select count(*) from organizations where name = ? and owner_platform_account_id ="
                + " (select id from platform_accounts where email = ?)",
            Integer.class,
            "Kiniela",
            email);
    assertThat(ownedOrganizations)
        .as("the created Organization must really be owned by this PlatformAccount, in the DB")
        .isEqualTo(1);

    // BR-ID-04's ADR-0012 equivalent: resetting the password signs the dashboard session out —
    // the very next request with the same session cookie must be rejected, not just accepted
    // with stale credentials.
    requestPasswordReset(email);
    ArgumentCaptor<String> resetToken = ArgumentCaptor.forClass(String.class);
    verify(mailSender).sendPlatformAccountPasswordReset(eq(email), resetToken.capture());
    HttpResponse<Void> resetResponse =
        submitResetPassword(resetToken.getValue(), "a-brand-new-Str0ng-password!");
    assertThat(resetResponse.statusCode()).isEqualTo(302);

    HttpResponse<String> dashboardAfterReset = getDashboard();
    assertThat(dashboardAfterReset.statusCode())
        .as("the old, now-reset-invalidated session must no longer reach the dashboard")
        .isEqualTo(302);
    assertThat(dashboardAfterReset.headers().firstValue("Location").orElseThrow())
        .contains("/platform/login");
  }

  // Security finding (SDE-III review, 2026-08-22), regression test for its fix: before this fix,
  // both tiers shared the one app-wide SecurityContextRepository bean, and
  // PlatformDashboardSecurityConfig only checked .anyRequest().authenticated() — a plain tenant
  // Account's session, established entirely via /o/{organizationId}/login and never once touching
  // /platform/login, satisfied that check and reached a fully functional dashboard. Confirmed live
  // pre-fix (HTTP 200, real dashboard HTML); this test proves the fix rejects it instead.
  @Test
  void aTenantAccountSessionCannotReachThePlatformDashboard() throws Exception {
    UUID organizationId = UUID.randomUUID();
    registerTenantAccount(organizationId, "tenant@example.com", "the-tenant-password");
    assertThat(
            loginAsTenant(organizationId, "tenant@example.com", "the-tenant-password").statusCode())
        .isEqualTo(302);

    HttpResponse<Void> dashboardResponse = getDashboardDiscardingBody();

    assertThat(dashboardResponse.statusCode())
        .as("a tenant Account's session must not be treated as an authenticated PlatformAccount")
        .isEqualTo(302);
    assertThat(dashboardResponse.headers().firstValue("Location").orElseThrow())
        .contains("/platform/login");
  }

  private void registerTenantAccount(UUID organizationId, String email, String password)
      throws IOException, InterruptedException {
    HttpResponse<String> form =
        httpClient.send(
            HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/register")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    String csrfToken = extractCsrfToken(form.body());

    String body =
        "_csrf="
            + csrfToken
            + "&email="
            + email
            + "&password="
            + password
            + "&confirmPassword="
            + password;
    HttpResponse<Void> response =
        httpClient.send(
            HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/register"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.discarding());
    assertThat(response.statusCode()).isEqualTo(302);
  }

  private HttpResponse<Void> loginAsTenant(UUID organizationId, String email, String password)
      throws IOException, InterruptedException {
    HttpResponse<String> form =
        httpClient.send(
            HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/login")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    String csrfToken = extractCsrfToken(form.body());

    String body = "_csrf=" + csrfToken + "&email=" + email + "&password=" + urlEncode(password);
    return httpClient.send(
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/login"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.discarding());
  }

  private HttpResponse<Void> getDashboardDiscardingBody() throws IOException, InterruptedException {
    return httpClient.send(
        HttpRequest.newBuilder(baseUri("/platform/dashboard")).GET().build(),
        HttpResponse.BodyHandlers.discarding());
  }

  private void registerPlatformAccount(String email, String password)
      throws IOException, InterruptedException {
    HttpResponse<String> form =
        httpClient.send(
            HttpRequest.newBuilder(baseUri("/platform/register")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    String csrfToken = extractCsrfToken(form.body());

    String body =
        "_csrf="
            + csrfToken
            + "&email="
            + email
            + "&password="
            + password
            + "&confirmPassword="
            + password;
    HttpResponse<Void> response =
        httpClient.send(
            HttpRequest.newBuilder(baseUri("/platform/register"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.discarding());
    assertThat(response.statusCode()).isEqualTo(302);
  }

  private HttpResponse<Void> login(String email, String password)
      throws IOException, InterruptedException {
    HttpResponse<String> form =
        httpClient.send(
            HttpRequest.newBuilder(baseUri("/platform/login")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    String csrfToken = extractCsrfToken(form.body());

    String body = "_csrf=" + csrfToken + "&email=" + email + "&password=" + urlEncode(password);
    return httpClient.send(
        HttpRequest.newBuilder(baseUri("/platform/login"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.discarding());
  }

  private HttpResponse<String> getVerifyEmail(String token)
      throws IOException, InterruptedException {
    return httpClient.send(
        HttpRequest.newBuilder(baseUri("/platform/verify-email?token=" + urlEncode(token)))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> getDashboard() throws IOException, InterruptedException {
    return httpClient.send(
        HttpRequest.newBuilder(baseUri("/platform/dashboard")).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<Void> createOrganization(String name)
      throws IOException, InterruptedException {
    HttpResponse<String> dashboard = getDashboard();
    String csrfToken = extractCsrfToken(dashboard.body());

    String body = "_csrf=" + csrfToken + "&name=" + urlEncode(name);
    return httpClient.send(
        HttpRequest.newBuilder(baseUri("/platform/dashboard"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.discarding());
  }

  private void requestPasswordReset(String email) throws IOException, InterruptedException {
    HttpResponse<String> form =
        httpClient.send(
            HttpRequest.newBuilder(baseUri("/platform/forgot-password")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    String csrfToken = extractCsrfToken(form.body());

    String body = "_csrf=" + csrfToken + "&email=" + email;
    HttpResponse<Void> response =
        httpClient.send(
            HttpRequest.newBuilder(baseUri("/platform/forgot-password"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.discarding());
    assertThat(response.statusCode()).isEqualTo(302);
  }

  private HttpResponse<Void> submitResetPassword(String token, String newPassword)
      throws IOException, InterruptedException {
    HttpResponse<String> form =
        httpClient.send(
            HttpRequest.newBuilder(baseUri("/platform/reset-password?token=" + urlEncode(token)))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    String csrfToken = extractCsrfToken(form.body());

    String body =
        "_csrf="
            + csrfToken
            + "&token="
            + urlEncode(token)
            + "&newPassword="
            + urlEncode(newPassword)
            + "&confirmPassword="
            + urlEncode(newPassword);
    return httpClient.send(
        HttpRequest.newBuilder(baseUri("/platform/reset-password"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.discarding());
  }

  private static String extractCsrfToken(String html) {
    Matcher matcher = CSRF_TOKEN_PATTERN.matcher(html);
    assertThat(matcher.find()).as("page must render a _csrf hidden input").isTrue();
    return matcher.group(1);
  }

  private static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private URI baseUri(String path) {
    return URI.create("http://localhost:" + port + path);
  }
}
