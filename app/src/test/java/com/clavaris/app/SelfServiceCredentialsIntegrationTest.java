package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.app.support.TestMailSenderConfig;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * TD-FUT-043 end to end: real HTTP, real Redis-backed sessions, real Postgres. Password change
 * keeps the session in use and ends the others; email change needs the password and the emailed
 * link, and the link works only in the session of the Account that asked for it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret",
      "clavaris.security-txt.contact=mailto:security@example.test",
      "clavaris.security-txt.expires=2099-01-01T00:00:00Z"
    })
class SelfServiceCredentialsIntegrationTest extends RedisBackedIntegrationTest {

  private static final Pattern CSRF_TOKEN_PATTERN =
      Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");
  private static final String PASSWORD = "a-correct-password";
  private static final String NEW_PASSWORD = "another-correct-password";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Autowired private PlatformAccountRepository platformAccounts;
  @Autowired private MailSender mailSender;

  private final ObjectMapper objectMapper = new ObjectMapper();

  // True if any mail went out while a database transaction was open on the sending thread.
  private final AtomicBoolean mailSentInsideTransaction = new AtomicBoolean();

  // The mail provider is a network call; none of these services may hold a pooled connection while
  // waiting on it (the TD-PERF-031 discipline), so every send records whether a transaction was
  // active at that moment.
  @BeforeEach
  void watchForMailInsideTransactions() {
    mailSentInsideTransaction.set(false);
    doAnswer(
            invocation -> {
              if (TransactionSynchronizationManager.isActualTransactionActive()) {
                mailSentInsideTransaction.set(true);
              }
              return null;
            })
        .when(mailSender)
        .sendPasswordChangedNotice(any(), any());
    doAnswer(
            invocation -> {
              if (TransactionSynchronizationManager.isActualTransactionActive()) {
                mailSentInsideTransaction.set(true);
              }
              return null;
            })
        .when(mailSender)
        .sendEmailChangeVerification(any(), any(), any());
    doAnswer(
            invocation -> {
              if (TransactionSynchronizationManager.isActualTransactionActive()) {
                mailSentInsideTransaction.set(true);
              }
              return null;
            })
        .when(mailSender)
        .sendEmailChangeNotice(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  void changingThePasswordKeepsThisSessionEndsTheOthersAndSwitchesTheCredential() throws Exception {
    UUID org = createOrganization();
    String email = "pw-change@example.com";
    registerAccount(org, email, PASSWORD);
    HttpClient here = newSessionBackedClient();
    HttpClient elsewhere = newSessionBackedClient();
    login(here, org, email, PASSWORD);
    login(elsewhere, org, email, PASSWORD);

    HttpResponse<String> changed =
        post(
            here,
            org,
            "/account/password",
            "currentPassword="
                + PASSWORD
                + "&newPassword="
                + NEW_PASSWORD
                + "&confirmPassword="
                + NEW_PASSWORD);

    assertThat(changed.statusCode()).isEqualTo(302);
    assertThat(changed.headers().firstValue("Location").orElseThrow()).contains("passwordChanged");
    assertThat(get(here, org, "/account/profile").statusCode()).isEqualTo(200);
    assertThat(get(elsewhere, org, "/account/profile").statusCode()).isEqualTo(302);
    verify(mailSender).sendPasswordChangedNotice(eq(email), any());
    assertThat(mailSentInsideTransaction).isFalse();
    assertThat(canLogin(org, email, NEW_PASSWORD)).isTrue();
    assertThat(canLogin(org, email, PASSWORD)).isFalse();
  }

  @Test
  void aWrongCurrentPasswordIsShownAsAFieldErrorAndChangesNothing() throws Exception {
    UUID org = createOrganization();
    String email = "pw-wrong@example.com";
    registerAccount(org, email, PASSWORD);
    HttpClient client = newSessionBackedClient();
    login(client, org, email, PASSWORD);

    HttpResponse<String> response =
        post(
            client,
            org,
            "/account/password",
            "currentPassword=not-it&newPassword="
                + NEW_PASSWORD
                + "&confirmPassword="
                + NEW_PASSWORD);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("current password is incorrect");
    assertThat(canLogin(org, email, PASSWORD)).isTrue();
  }

  @Test
  void theEmailChangesOnlyAfterTheLinkSentToTheNewAddressIsOpened() throws Exception {
    UUID org = createOrganization();
    String email = "mail-change@example.com";
    String newEmail = "mail-moved@example.com";
    registerAccount(org, email, PASSWORD);
    HttpClient client = newSessionBackedClient();
    login(client, org, email, PASSWORD);

    HttpResponse<String> requested =
        post(
            client, org, "/account/email", "newEmail=" + newEmail + "&currentPassword=" + PASSWORD);

    assertThat(requested.statusCode()).isEqualTo(302);
    assertThat(canLogin(org, email, PASSWORD)).isTrue();
    ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
    verify(mailSender).sendEmailChangeVerification(eq(newEmail), any(), token.capture());
    verify(mailSender).sendEmailChangeNotice(eq(email), any(), eq(newEmail), eq(false));

    // A mail scanner opening the link must not use it up: the GET only asks for a click.
    HttpResponse<String> opened =
        get(client, org, "/account/email/confirm?token=" + token.getValue());
    assertThat(opened.body()).contains("Confirm email change");
    assertThat(canLogin(org, newEmail, PASSWORD)).as("opening the link changes nothing").isFalse();

    HttpResponse<String> confirmed =
        post(client, org, "/account/email/confirm", "token=" + token.getValue());

    assertThat(confirmed.statusCode()).isEqualTo(200);
    assertThat(confirmed.body()).contains("has been changed");
    assertThat(canLogin(org, newEmail, PASSWORD)).isTrue();
    assertThat(canLogin(org, email, PASSWORD)).isFalse();
    verify(mailSender).sendEmailChangeNotice(eq(email), any(), eq(newEmail), eq(true));
    assertThat(mailSentInsideTransaction).isFalse();
    // Single use: the same link a second time is refused.
    assertThat(
            post(client, org, "/account/email/confirm", "token=" + token.getValue()).statusCode())
        .isEqualTo(400);
  }

  @Test
  void theLinkIsUselessInAnotherAccountsSession() throws Exception {
    UUID org = createOrganization();
    registerAccount(org, "asker@example.com", PASSWORD);
    registerAccount(org, "bystander@example.com", PASSWORD);
    HttpClient asker = newSessionBackedClient();
    HttpClient bystander = newSessionBackedClient();
    login(asker, org, "asker@example.com", PASSWORD);
    login(bystander, org, "bystander@example.com", PASSWORD);
    post(
        asker, org, "/account/email", "newEmail=asker-new@example.com&currentPassword=" + PASSWORD);
    ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
    verify(mailSender)
        .sendEmailChangeVerification(eq("asker-new@example.com"), any(), token.capture());

    assertThat(
            post(bystander, org, "/account/email/confirm", "token=" + token.getValue())
                .statusCode())
        .isEqualTo(400);
    assertThat(canLogin(org, "asker@example.com", PASSWORD)).isTrue();
  }

  @Test
  void anAddressAlreadyInUseLooksLikeSuccessButNothingIsSent() throws Exception {
    UUID org = createOrganization();
    registerAccount(org, "wants-move@example.com", PASSWORD);
    registerAccount(org, "occupied@example.com", PASSWORD);
    HttpClient client = newSessionBackedClient();
    login(client, org, "wants-move@example.com", PASSWORD);

    HttpResponse<String> response =
        post(
            client,
            org,
            "/account/email",
            "newEmail=occupied@example.com&currentPassword=" + PASSWORD);

    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(response.headers().firstValue("Location").orElseThrow())
        .contains("emailChangeRequested");
    verify(mailSender, never())
        .sendEmailChangeVerification(eq("occupied@example.com"), any(), anyString());
  }

  @Test
  void disconnectingAProviderThatIsNotLinkedIsAnErrorNotACrash() throws Exception {
    UUID org = createOrganization();
    registerAccount(org, "no-social@example.com", PASSWORD);
    HttpClient client = newSessionBackedClient();
    login(client, org, "no-social@example.com", PASSWORD);

    assertThat(get(client, org, "/account/connections").statusCode()).isEqualTo(200);
    HttpResponse<String> response = post(client, org, "/account/connections/github/disconnect", "");

    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(response.headers().firstValue("Location").orElseThrow()).contains("connectError");
  }

  // BR-ID-04: an address change must not leave a reset started before it usable by whoever still
  // reads the old mailbox.
  @Test
  void aPasswordResetMailedToTheOldAddressStopsWorkingOnceTheEmailChanges() throws Exception {
    UUID org = createOrganization();
    String email = "reset-then-move@example.com";
    String newEmail = "reset-moved@example.com";
    registerAccount(org, email, PASSWORD);
    requestPasswordReset(org, email);
    ArgumentCaptor<String> resetToken = ArgumentCaptor.forClass(String.class);
    verify(mailSender).sendPasswordReset(eq(email), any(), resetToken.capture());
    HttpClient client = newSessionBackedClient();
    login(client, org, email, PASSWORD);
    post(client, org, "/account/email", "newEmail=" + newEmail + "&currentPassword=" + PASSWORD);
    ArgumentCaptor<String> emailToken = ArgumentCaptor.forClass(String.class);
    verify(mailSender).sendEmailChangeVerification(eq(newEmail), any(), emailToken.capture());
    post(client, org, "/account/email/confirm", "token=" + emailToken.getValue());

    HttpResponse<String> reset =
        resetPassword(org, resetToken.getValue(), "attacker-chosen-passphrase-9");

    assertThat(reset.statusCode()).as("the old reset link is dead").isNotEqualTo(302);
    assertThat(canLogin(org, newEmail, PASSWORD)).isTrue();
  }

  @Test
  void connectedAccountsActionsAreRateLimitedPerAccount() throws Exception {
    UUID org = createOrganization();
    registerAccount(org, "connections-limit@example.com", PASSWORD);
    HttpClient client = newSessionBackedClient();
    login(client, org, "connections-limit@example.com", PASSWORD);
    int limit = 10;

    for (int attempt = 1; attempt <= limit; attempt++) {
      assertThat(post(client, org, "/account/connections/github/disconnect", "").statusCode())
          .isEqualTo(302);
    }

    assertThat(post(client, org, "/account/connections/github/disconnect", "").statusCode())
        .isEqualTo(429);
  }

  private void requestPasswordReset(final UUID org, final String email)
      throws IOException, InterruptedException {
    HttpClient client = newSessionBackedClient();
    String csrf = extractCsrfToken(get(client, org, "/forgot-password").body());
    client.send(
        HttpRequest.newBuilder(baseUri("/o/" + org + "/forgot-password"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + csrf + "&email=" + email))
            .build(),
        HttpResponse.BodyHandlers.discarding());
  }

  private HttpResponse<String> resetPassword(
      final UUID org, final String token, final String newPassword)
      throws IOException, InterruptedException {
    HttpClient client = newSessionBackedClient();
    String csrf = extractCsrfToken(get(client, org, "/reset-password?token=" + token).body());
    return client.send(
        HttpRequest.newBuilder(baseUri("/o/" + org + "/reset-password"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "_csrf="
                        + csrf
                        + "&token="
                        + token
                        + "&newPassword="
                        + newPassword
                        + "&confirmPassword="
                        + newPassword))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  // TD-FUT-050 / TD-TEST-009: served through the real default chain, publicly, without a session.
  @Test
  void securityTxtIsServedPubliclyThroughTheRealFilterChains() throws Exception {
    HttpClient client = newSessionBackedClient();

    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(baseUri("/.well-known/security.txt")).GET().build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type").orElseThrow())
        .startsWith("text/plain");
    assertThat(response.body())
        .contains("Contact: mailto:security@example.test")
        .contains("Expires: 2099-01-01T00:00:00Z")
        .contains("Canonical: ");
  }

  // TD-SEC-071: every chain sends the header, the tenant pages, the platform pages, the OIDC
  // endpoints and the catch-all alike, since tokens travel in the URL of several of them.
  @Test
  void everyFilterChainSendsNoReferrer() throws Exception {
    UUID org = createOrganization();
    HttpClient client = newSessionBackedClient();

    for (String path :
        new String[] {
          "/o/" + org + "/login",
          "/o/" + org + "/oauth2/jwks",
          "/o/" + org + "/.well-known/openid-configuration",
          "/platform/login",
          "/"
        }) {
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(baseUri(path)).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(response.headers().firstValue("Referrer-Policy"))
          .as("Referrer-Policy on " + path)
          .hasValue("no-referrer");
    }
  }

  private boolean canLogin(final UUID org, final String email, final String password)
      throws IOException, InterruptedException {
    HttpClient client = newSessionBackedClient();
    login(client, org, email, password);
    return get(client, org, "/account/profile").statusCode() == 200;
  }

  private HttpResponse<String> get(final HttpClient client, final UUID org, final String path)
      throws IOException, InterruptedException {
    return client.send(
        HttpRequest.newBuilder(baseUri("/o/" + org + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  // Fetches the page first for a CSRF token, as a browser would, then posts the form.
  private HttpResponse<String> post(
      final HttpClient client, final UUID org, final String path, final String form)
      throws IOException, InterruptedException {
    String csrf = extractCsrfToken(get(client, org, "/account/profile").body());
    return client.send(
        HttpRequest.newBuilder(baseUri("/o/" + org + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + csrf + "&" + form))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private void login(
      final HttpClient client, final UUID organizationId, final String email, final String password)
      throws IOException, InterruptedException {
    HttpResponse<String> formResponse = get(client, organizationId, "/login");
    String csrfToken = extractCsrfToken(formResponse.body());
    HttpRequest loginRequest =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/login"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "_csrf=" + csrfToken + "&email=" + email + "&password=" + password))
            .build();
    client.send(loginRequest, HttpResponse.BodyHandlers.discarding());
  }

  private HttpClient newSessionBackedClient() {
    return HttpClient.newBuilder()
        .cookieHandler(new CookieManager())
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  private UUID createOrganization() throws IOException, InterruptedException {
    HttpClient bootstrapClient = newSessionBackedClient();
    String basicAuth =
        Base64.getEncoder()
            .encodeToString("test-platform-client:a-test-platform-secret".getBytes());
    HttpRequest tokenRequest =
        HttpRequest.newBuilder(baseUri("/oauth2/token"))
            .header("Authorization", "Basic " + basicAuth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "grant_type=client_credentials&scope=platform:organizations:write"))
            .build();
    HttpResponse<String> tokenResponse =
        bootstrapClient.send(tokenRequest, HttpResponse.BodyHandlers.ofString());
    String platformToken =
        objectMapper.readTree(tokenResponse.body()).get("access_token").asString();

    PlatformAccount owner =
        PlatformAccount.register(new Email("owner-" + UUID.randomUUID() + "@example.test"));
    owner.attachPasswordCredential("not-a-real-hash-this-test-never-logs-in");
    platformAccounts.save(owner);

    HttpRequest orgRequest =
        HttpRequest.newBuilder(baseUri("/api/v1/admin/organizations"))
            .header("Authorization", "Bearer " + platformToken)
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"name\":\"Sessions Page Co\",\"ownerPlatformAccountId\":\""
                        + owner.id().value()
                        + "\"}"))
            .build();
    HttpResponse<String> orgResponse =
        bootstrapClient.send(orgRequest, HttpResponse.BodyHandlers.ofString());
    return UUID.fromString(objectMapper.readTree(orgResponse.body()).get("id").asString());
  }

  private void registerAccount(final UUID organizationId, final String email, final String password)
      throws IOException, InterruptedException {
    HttpClient client = newSessionBackedClient();
    HttpRequest getForm =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/register")).GET().build();
    HttpResponse<String> formResponse = client.send(getForm, HttpResponse.BodyHandlers.ofString());
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
    client.send(register, HttpResponse.BodyHandlers.discarding());
  }

  private static String extractCsrfToken(final String html) {
    Matcher matcher = CSRF_TOKEN_PATTERN.matcher(html);
    assertThat(matcher.find()).as("page must render a _csrf hidden input").isTrue();
    return matcher.group(1);
  }

  private URI baseUri(final String path) {
    return URI.create("http://localhost:" + port + path);
  }
}
