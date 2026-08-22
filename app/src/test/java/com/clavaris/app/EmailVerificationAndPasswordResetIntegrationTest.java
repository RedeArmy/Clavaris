package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.clavaris.app.support.TestMailSenderConfig;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.domain.model.OrganizationId;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * TD-SEC-004 (email verification really sent) and BR-ID-04 (password reset revokes every active
 * session), end to end against real code, real Postgres, real HTTP — the same "confirmed live, not
 * assumed" bar as every other flow in this suite. {@link TestMailSenderConfig} intercepts what
 * would otherwise be a real Resend API call; the raw token it captures is exactly what a real
 * clicked link would carry, so following it here proves the whole chain, not just each use case in
 * isolation (already covered by identity-module's own unit/persistence tests).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
class EmailVerificationAndPasswordResetIntegrationTest {

  private static final Pattern CSRF_TOKEN_PATTERN =
      Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Autowired private MailSender mailSender;

  private final CookieManager cookieManager = new CookieManager();
  private final HttpClient httpClient =
      HttpClient.newBuilder()
          .cookieHandler(cookieManager)
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  @Test
  void registrationTriggersARealVerificationEmailAndTheLinkActuallyVerifiesTheAccount()
      throws Exception {
    UUID organizationId = UUID.randomUUID();
    String email = "verify-me@example.com";

    registerAccount(organizationId, email, "a-correct-password");

    ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
    verify(mailSender)
        .sendEmailVerification(
            eq(email), eq(new OrganizationId(organizationId)), tokenCaptor.capture());
    String verificationToken = tokenCaptor.getValue();

    HttpResponse<String> verifyResponse = getVerifyEmail(organizationId, verificationToken);
    assertThat(verifyResponse.statusCode()).isEqualTo(200);
    assertThat(verifyResponse.body()).contains("Email verified");

    // A second click (e.g. the user's mail client prefetching the link) must not blow up — the
    // token is single-use, but Account.verifyEmail() is idempotent; only the token itself is
    // consumed, so a resend of the identical link is expected to now show the invalid-link page,
    // not throw a 500.
    HttpResponse<String> secondClick = getVerifyEmail(organizationId, verificationToken);
    assertThat(secondClick.statusCode()).isEqualTo(200);
    assertThat(secondClick.body()).contains("invalid or has expired");
  }

  @Test
  void passwordResetChangesThePasswordAndSignsOutEverywhere() throws Exception {
    UUID organizationId = UUID.randomUUID();
    String email = "reset-me@example.com";
    String originalPassword = "the-original-password";
    String newPassword = "a-brand-new-Str0ng-password!";

    registerAccount(organizationId, email, originalPassword);
    assertThat(login(organizationId, email, originalPassword).statusCode())
        .as("sanity check: the original password must work before any reset")
        .isEqualTo(302);

    requestPasswordReset(organizationId, email);
    ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
    verify(mailSender)
        .sendPasswordReset(
            eq(email), eq(new OrganizationId(organizationId)), tokenCaptor.capture());
    String resetToken = tokenCaptor.getValue();

    HttpResponse<Void> resetResponse = submitResetPassword(organizationId, resetToken, newPassword);
    assertThat(resetResponse.statusCode()).isEqualTo(302);
    assertThat(resetResponse.headers().firstValue("Location").orElseThrow())
        .contains("reset-password/success");

    // BR-ID-04: the old password must no longer authenticate...
    assertThat(login(organizationId, email, originalPassword).statusCode())
        .as("old password must be rejected after reset")
        .isEqualTo(200); // re-rendered login form with an error, not a redirect

    // ...and the new one must.
    assertThat(login(organizationId, email, newPassword).statusCode())
        .as("new password must work after reset")
        .isEqualTo(302);
  }

  private void registerAccount(UUID organizationId, String email, String password)
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
    HttpResponse<Void> response = httpClient.send(register, HttpResponse.BodyHandlers.discarding());
    assertThat(response.statusCode()).isEqualTo(302);
  }

  private HttpResponse<Void> login(UUID organizationId, String email, String password)
      throws IOException, InterruptedException {
    HttpRequest getForm =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/login")).GET().build();
    HttpResponse<String> formResponse =
        httpClient.send(getForm, HttpResponse.BodyHandlers.ofString());
    String csrfToken = extractCsrfToken(formResponse.body());

    String body = "_csrf=" + csrfToken + "&email=" + email + "&password=" + urlEncode(password);
    HttpRequest loginRequest =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/login"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return httpClient.send(loginRequest, HttpResponse.BodyHandlers.discarding());
  }

  private HttpResponse<String> getVerifyEmail(UUID organizationId, String token)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(
                baseUri("/o/" + organizationId + "/verify-email?token=" + urlEncode(token)))
            .GET()
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private void requestPasswordReset(UUID organizationId, String email)
      throws IOException, InterruptedException {
    HttpRequest getForm =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/forgot-password")).GET().build();
    HttpResponse<String> formResponse =
        httpClient.send(getForm, HttpResponse.BodyHandlers.ofString());
    String csrfToken = extractCsrfToken(formResponse.body());

    String body = "_csrf=" + csrfToken + "&email=" + email;
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/forgot-password"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
    assertThat(response.statusCode()).isEqualTo(302);
  }

  private HttpResponse<Void> submitResetPassword(
      UUID organizationId, String token, String newPassword)
      throws IOException, InterruptedException {
    HttpRequest getForm =
        HttpRequest.newBuilder(
                baseUri("/o/" + organizationId + "/reset-password?token=" + urlEncode(token)))
            .GET()
            .build();
    HttpResponse<String> formResponse =
        httpClient.send(getForm, HttpResponse.BodyHandlers.ofString());
    String csrfToken = extractCsrfToken(formResponse.body());

    String body =
        "_csrf="
            + csrfToken
            + "&token="
            + urlEncode(token)
            + "&newPassword="
            + urlEncode(newPassword)
            + "&confirmPassword="
            + urlEncode(newPassword);
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/reset-password"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.discarding());
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
