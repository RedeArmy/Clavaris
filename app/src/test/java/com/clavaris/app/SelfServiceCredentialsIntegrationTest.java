package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret"
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

    HttpResponse<String> confirmed =
        get(client, org, "/account/email/confirm?token=" + token.getValue());

    assertThat(confirmed.statusCode()).isEqualTo(200);
    assertThat(confirmed.body()).contains("has been changed");
    assertThat(canLogin(org, newEmail, PASSWORD)).isTrue();
    assertThat(canLogin(org, email, PASSWORD)).isFalse();
    verify(mailSender).sendEmailChangeNotice(eq(email), any(), eq(newEmail), eq(true));
    // Single use: the same link a second time is refused.
    assertThat(get(client, org, "/account/email/confirm?token=" + token.getValue()).statusCode())
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

    assertThat(get(bystander, org, "/account/email/confirm?token=" + token.getValue()).statusCode())
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
