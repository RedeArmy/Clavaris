package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
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
 * End-to-end proof of both features from the "self-service sessions/devices page + new-device login
 * email" work: real HTTP, real Redis-backed {@code HttpSession} (device-attribute capture and the
 * {@code AccountSessionsController} page itself), real Postgres ({@code known_devices}). {@link
 * TestMailSenderConfig} swaps in a Mockito mock so this suite verifies notification calls, not a
 * real Resend request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret"
    })
class AccountSessionsIntegrationTest extends RedisBackedIntegrationTest {

  private static final Pattern CSRF_TOKEN_PATTERN =
      Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");
  private static final Pattern REVOKE_ACTION_PATTERN =
      Pattern.compile("/account/sessions/([^/\"]+)/revoke");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Autowired private PlatformAccountRepository platformAccounts;
  @Autowired private MailSender mailSender;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void aFirstLoginShowsUpOnTheSessionsPageAndTriggersExactlyOneNotification() throws Exception {
    UUID organizationId = createOrganization();
    String email = "device-test@example.com";
    registerAccount(organizationId, email, "a-correct-password");
    HttpClient client = newSessionBackedClient();

    login(client, organizationId, email, "a-correct-password", "device-A");
    String sessionsPage = getSessionsPage(client, organizationId);

    assertThat(sessionsPage).contains("device-A");
    verify(mailSender, times(1))
        .sendNewDeviceLoginNotification(eq(email), any(), eq("device-A"), any(), any());
  }

  @Test
  void aSecondLoginFromTheSameDeviceSendsNoAdditionalNotification() throws Exception {
    UUID organizationId = createOrganization();
    String email = "same-device@example.com";
    registerAccount(organizationId, email, "a-correct-password");

    login(newSessionBackedClient(), organizationId, email, "a-correct-password", "device-A");
    // A brand-new HttpSession/cookie jar (a fresh "login instance") but the identical User-Agent
    // fingerprint — the whole point of KnownDevice outliving any one HttpSession.
    login(newSessionBackedClient(), organizationId, email, "a-correct-password", "device-A");

    verify(mailSender, times(1))
        .sendNewDeviceLoginNotification(eq(email), any(), eq("device-A"), any(), any());
  }

  @Test
  void aLoginFromADifferentDeviceSendsItsOwnNotification() throws Exception {
    UUID organizationId = createOrganization();
    String email = "two-devices@example.com";
    registerAccount(organizationId, email, "a-correct-password");

    login(newSessionBackedClient(), organizationId, email, "a-correct-password", "device-A");
    login(newSessionBackedClient(), organizationId, email, "a-correct-password", "device-B");

    verify(mailSender)
        .sendNewDeviceLoginNotification(eq(email), any(), eq("device-A"), any(), any());
    verify(mailSender)
        .sendNewDeviceLoginNotification(eq(email), any(), eq("device-B"), any(), any());
  }

  @Test
  void revokingASessionSignsThatBrowserOutOnItsNextRequest() throws Exception {
    UUID organizationId = createOrganization();
    String email = "revoke-me@example.com";
    registerAccount(organizationId, email, "a-correct-password");
    HttpClient client = newSessionBackedClient();
    login(client, organizationId, email, "a-correct-password", "device-A");
    String sessionsPage = getSessionsPage(client, organizationId);
    String csrfToken = extractCsrfToken(sessionsPage);
    String sessionId = extractRevokeSessionId(sessionsPage);

    HttpResponse<String> revokeResponse = revoke(client, organizationId, sessionId, csrfToken);
    assertThat(revokeResponse.statusCode()).isEqualTo(302);

    // TD-SEC-031's own mechanism (expireNow + TenantSessionConcurrencyFilter +
    // InvalidateAndContinueSessionExpiredStrategy): the SAME still-cookied browser, on its very
    // next request, is bounced to login instead of reaching the page it asked for.
    HttpResponse<String> afterRevoke = getSessionsPageRaw(client, organizationId);
    assertThat(afterRevoke.statusCode()).isEqualTo(302);
    assertThat(afterRevoke.headers().firstValue("Location").orElseThrow()).contains("/login");
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

  private void login(
      final HttpClient client,
      final UUID organizationId,
      final String email,
      final String password,
      final String userAgent)
      throws IOException, InterruptedException {
    HttpRequest getForm =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/login"))
            .header("User-Agent", userAgent)
            .GET()
            .build();
    HttpResponse<String> formResponse = client.send(getForm, HttpResponse.BodyHandlers.ofString());
    String csrfToken = extractCsrfToken(formResponse.body());

    String body = "_csrf=" + csrfToken + "&email=" + email + "&password=" + password;
    HttpRequest loginRequest =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/login"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("User-Agent", userAgent)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    client.send(loginRequest, HttpResponse.BodyHandlers.discarding());
  }

  private String getSessionsPage(final HttpClient client, final UUID organizationId)
      throws IOException, InterruptedException {
    return getSessionsPageRaw(client, organizationId).body();
  }

  private HttpResponse<String> getSessionsPageRaw(
      final HttpClient client, final UUID organizationId) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/account/sessions")).GET().build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> revoke(
      final HttpClient client,
      final UUID organizationId,
      final String sessionId,
      final String csrfToken)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(
                baseUri("/o/" + organizationId + "/account/sessions/" + sessionId + "/revoke"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + csrfToken))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static String extractCsrfToken(final String html) {
    Matcher matcher = CSRF_TOKEN_PATTERN.matcher(html);
    assertThat(matcher.find()).as("page must render a _csrf hidden input").isTrue();
    return matcher.group(1);
  }

  private static String extractRevokeSessionId(final String html) {
    Matcher matcher = REVOKE_ACTION_PATTERN.matcher(html);
    assertThat(matcher.find()).as("page must render at least one revoke form").isTrue();
    return matcher.group(1);
  }

  private URI baseUri(final String path) {
    return URI.create("http://localhost:" + port + path);
  }
}
