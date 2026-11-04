package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.clavaris.app.support.PlatformApiTestSupport;
import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.app.support.TestMailSenderConfig;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
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
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR-0041: the two Organization switches for passkeys against real Postgres and Redis. Both are on
 * for an Organization that never touched them; switching passkeys off removes the sign-in button,
 * refuses both sign-in steps and every registration step, and switching only the offer off leaves
 * passkeys working but ends the page that follows an emailed-code sign-in.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret",
      "clavaris.auth.policy-cache-ttl-seconds=0"
    })
class PasskeySwitchesIntegrationTest extends RedisBackedIntegrationTest {

  private static final Pattern CSRF_TOKEN_PATTERN =
      Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");
  private static final String PASSWORD = "a-correct-password";
  private static final String SCOPES =
      "platform:organizations:write platform:account-authentication-policy:write";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Autowired private PlatformAccountRepository platformAccounts;
  @Autowired private MailSender mailSender;

  private final HttpClient apiClient = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper = new ObjectMapper();

  private final class Browser {
    private final HttpClient client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    HttpResponse<String> get(final String path) throws Exception {
      return client.send(
          HttpRequest.newBuilder(baseUri(path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> postForm(final String path, final String csrfPage, final String fields)
        throws Exception {
      final String csrf = csrf(get(csrfPage).body());
      return client.send(
          HttpRequest.newBuilder(baseUri(path))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + csrf + fields))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> postJson(final String path, final String csrfPage, final String body)
        throws Exception {
      final String csrf = csrf(get(csrfPage).body());
      return client.send(
          HttpRequest.newBuilder(baseUri(path))
              .header("Content-Type", "application/json")
              .header("X-CSRF-TOKEN", csrf)
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> passwordSignIn(final UUID organizationId, final String email)
        throws Exception {
      return postForm(
          "/o/" + organizationId + "/login",
          "/o/" + organizationId + "/login",
          "&email=" + enc(email) + "&password=" + enc(PASSWORD));
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

  @Test
  void anOrganizationThatNeverTouchedThemHasBothOn() throws Exception {
    final String token = platformToken();
    final UUID organizationId = createProductionOrganization(token, "Passkey Default Co");

    final JsonNode policy = setPolicy(token, organizationId, null, null);

    assertThat(policy.get("passkeysEnabled").asBoolean()).isTrue();
    assertThat(policy.get("passkeyOfferEnabled").asBoolean()).isTrue();
    assertThat(new Browser().get("/o/" + organizationId + "/login").body())
        .contains("passkey-signin-button");
  }

  @Test
  void switchingPasskeysOffRemovesTheButtonAndRefusesBothSignInSteps() throws Exception {
    final String token = platformToken();
    final UUID organizationId = createProductionOrganization(token, "Passkey Off Co");
    final String loginPage = "/o/" + organizationId + "/login";

    final Browser on = new Browser();
    assertThat(on.postJson(loginPage + "/webauthn/start", loginPage, "{}").statusCode())
        .as("start answers while passkeys are on")
        .isEqualTo(200);

    setPolicy(token, organizationId, false, null);
    final Browser off = new Browser();

    assertThat(off.get(loginPage).body()).doesNotContain("passkey-signin-button");
    assertThat(off.postJson(loginPage + "/webauthn/start", loginPage, "{}").statusCode())
        .isEqualTo(403);
    assertThat(
            off.postJson(loginPage + "/webauthn/finish", loginPage, "{\"credential\":\"x\"}")
                .statusCode())
        .isEqualTo(403);
  }

  @Test
  void switchingPasskeysOffMakesTheAccountPageRefuseToAddOneButStillLoadsIt() throws Exception {
    final String token = platformToken();
    final UUID organizationId = createProductionOrganization(token, "Passkey Page Co");
    register(organizationId, "page@example.com");
    setPolicy(token, organizationId, false, null);
    final Browser browser = new Browser();
    browser.passwordSignIn(organizationId, "page@example.com");
    final String passkeys = "/o/" + organizationId + "/account/passkeys";

    final HttpResponse<String> page = browser.get(passkeys);
    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(page.body()).contains("Adding passkeys is not available");
    assertThat(
            browser
                .postJson(
                    passkeys + "/registration/start",
                    "/o/" + organizationId + "/account/profile",
                    "{}")
                .statusCode())
        .isEqualTo(403);
    assertThat(browser.get("/o/" + organizationId + "/account/profile").body())
        .doesNotContain("Your passkeys");
  }

  @Test
  void theOfferFollowsAnEmailedCodeSignInUntilTheOrganizationSwitchesItOff() throws Exception {
    final String token = platformToken();
    final UUID organizationId = createProductionOrganization(token, "Passkey Offer Co");
    setPolicy(token, organizationId, null, null);
    final String first = "offer-on@example.com";
    final String second = "offer-off@example.com";
    register(organizationId, first);
    register(organizationId, second);

    assertThat(location(codeSignIn(organizationId, first)))
        .as("the offer is on by default")
        .contains("/account/passkey-offer");

    setPolicy(token, organizationId, true, false);

    assertThat(location(codeSignIn(organizationId, second)))
        .as("switched off, the sign-in goes straight on")
        .contains("/login?authenticated")
        .doesNotContain("passkey-offer");
  }

  @Test
  void switchingPasskeysOffAlsoEndsTheOfferWhateverTheOfferSwitchSays() throws Exception {
    final String token = platformToken();
    final UUID organizationId = createProductionOrganization(token, "Passkey Both Co");
    setPolicy(token, organizationId, false, true);
    register(organizationId, "both@example.com");

    assertThat(location(codeSignIn(organizationId, "both@example.com")))
        .contains("/login?authenticated")
        .doesNotContain("passkey-offer");
  }

  // ---- helpers --------------------------------------------------------------------------------

  private HttpResponse<String> codeSignIn(final UUID organizationId, final String email)
      throws Exception {
    clearInvocations(mailSender);
    final Browser browser = new Browser();
    final String request = "/o/" + organizationId + "/login/email-code";
    browser.postForm(request, request, "&email=" + enc(email));
    final ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
    verify(mailSender, timeout(5000))
        .sendEmailSignInCode(eq(email), eq(new OrganizationId(organizationId)), code.capture());
    final String confirm = request + "/confirm";
    return browser.postForm(
        confirm,
        confirm + "?email=" + enc(email),
        "&email=" + enc(email) + "&code=" + code.getValue());
  }

  private String platformToken() throws IOException, InterruptedException {
    return PlatformApiTestSupport.platformAccessToken(
        apiClient, objectMapper, baseUri("/oauth2/token"), SCOPES);
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
                            + PlatformApiTestSupport.registerAPlatformAccount(platformAccounts)
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

  /** Sets the policy with the email code on; a null switch is left out so the default applies. */
  private JsonNode setPolicy(
      final String platformToken,
      final UUID organizationId,
      final Boolean passkeys,
      final Boolean offer)
      throws IOException, InterruptedException {
    final StringBuilder body =
        new StringBuilder(
            "{\"emailVerificationRequiredAtSignIn\": false, \"emailVerificationMethod\": \"LINK\","
                + " \"emailCodeSignInEnabled\": true, \"emailLinkSignInEnabled\": false,"
                + " \"usernameRequired\": false, \"usernameSignInEnabled\": false,"
                + " \"passwordAtSignUpEnabled\": true, \"deviceTrustEnabled\": false,"
                + " \"selfRegistrationRequiresApproval\": false");
    if (passkeys != null) {
      body.append(", \"passkeysEnabled\": ").append(passkeys);
    }
    if (offer != null) {
      body.append(", \"passkeyOfferEnabled\": ").append(offer);
    }
    body.append('}');
    final HttpResponse<String> response =
        apiClient.send(
            HttpRequest.newBuilder(
                    baseUri(
                        "/api/v1/admin/organizations/" + organizationId + "/authentication-policy"))
                .header("Authorization", "Bearer " + platformToken)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return objectMapper.readTree(response.body());
  }

  private void register(final UUID organizationId, final String email) throws Exception {
    final HttpResponse<String> response =
        new Browser()
            .postForm(
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
