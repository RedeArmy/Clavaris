package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.app.support.TestMailSenderConfig;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccount;
import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * TD-UX-001, proven end to end rather than by reading the templates: an application's brand colour
 * must actually reach the browser under the pages' own content security policy.
 *
 * <p>The colour used to travel in an inline {@code <style>}, which {@code style-src 'self'}
 * refuses, so every branded page rendered in the default colour and the only symptom was a console
 * warning. It now travels as a same-origin stylesheet. The unit tests cover the controller and the
 * interceptor separately; what none of them can show is the whole chain a browser walks, which is
 * what goes wrong silently: the real login page names the stylesheet, the stylesheet is reachable
 * with no session through the real security chains, and the policy on the page still allows it
 * without {@code unsafe-inline}. A colour that cannot reach the page fails here, not in a user's
 * console.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret"
    })
class BrandedConsumerPagesIntegrationTest extends RedisBackedIntegrationTest {

  private static final String REDIRECT_URI = "https://client.example.test/callback";
  private static final String BRAND_COLOUR = "#1A73E8";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Autowired private PlatformAccountRepository platformAccounts;

  // No cookie handler on purpose: a browser fetching a stylesheet link has no reason to carry the
  // visitor's session, and the page itself is the first thing it asks for.
  private final HttpClient httpClient =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void aBrandedLoginPageLinksItsColourAsAStylesheetTheItsOwnPolicyAllows() throws Exception {
    final Fixture fixture = fixtureWithBrandColour(BRAND_COLOUR);

    final HttpResponse<String> page =
        get("/o/" + fixture.organizationId() + "/login?clientId=" + fixture.clientId());

    assertThat(page.statusCode()).isEqualTo(200);
    final String themeUrl =
        "/o/" + fixture.organizationId() + "/branding/theme.css?clientId=" + fixture.clientId();
    assertThat(page.body())
        .as("the page must reference the brand stylesheet, not carry the colour inline")
        .containsPattern("<link[^>]+href=\"" + Pattern.quote(themeUrl) + "\"");
    assertThat(page.body())
        .as("an inline <style> is exactly what style-src 'self' refuses")
        .doesNotContain("<style");

    final String policy = page.headers().firstValue("Content-Security-Policy").orElse("");
    assertThat(policy)
        .as("the stylesheet is allowed because it is same-origin, not because the policy is loose")
        .contains("style-src 'self'")
        .doesNotContain("unsafe-inline");
  }

  @Test
  void theBrandStylesheetIsServedWithoutASession() throws Exception {
    final Fixture fixture = fixtureWithBrandColour(BRAND_COLOUR);

    final HttpResponse<String> stylesheet =
        get(
            "/o/"
                + fixture.organizationId()
                + "/branding/theme.css?clientId="
                + fixture.clientId());

    assertThat(stylesheet.statusCode()).isEqualTo(200);
    assertThat(stylesheet.headers().firstValue("Content-Type").orElse("")).startsWith("text/css");
    assertThat(stylesheet.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
    assertThat(stylesheet.body())
        .isEqualTo(":root { --clavaris-brand-color: " + BRAND_COLOUR + "; }\n");
  }

  // The page a person lands on most after the first one: sign-in re-rendered with an error. It is
  // the same URL (the form posts to itself, query string included), so it must keep the colour.
  @Test
  void theLoginPageReRenderedAfterAFailedSignInKeepsTheBrandColour() throws Exception {
    final Fixture fixture = fixtureWithBrandColour(BRAND_COLOUR);
    final String loginUrl =
        "/o/" + fixture.organizationId() + "/login?clientId=" + fixture.clientId();
    final HttpClient browser =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    final HttpResponse<String> form =
        browser.send(
            HttpRequest.newBuilder(uri(loginUrl)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    final Matcher csrf = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(form.body());
    assertThat(csrf.find()).isTrue();

    final HttpResponse<String> rejected =
        browser.send(
            HttpRequest.newBuilder(uri(loginUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "_csrf="
                            + csrf.group(1)
                            + "&email=nobody%40example.test&password=not-the-password"))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(rejected.statusCode()).isEqualTo(200);
    assertThat(rejected.body())
        .as("the error page must still carry the application's colour")
        .contains("branding/theme.css?clientId=" + fixture.clientId());
  }

  @Test
  void anApplicationWithNoBrandColourGetsNoStylesheetLink() throws Exception {
    final Fixture fixture = fixtureWithBrandColour(null);

    final HttpResponse<String> page =
        get("/o/" + fixture.organizationId() + "/login?clientId=" + fixture.clientId());

    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(page.body()).doesNotContain("branding/theme.css");
  }

  // ---- fixture: an Organization with one client, optionally with a brand colour ----

  private record Fixture(UUID organizationId, String clientId) {}

  private Fixture fixtureWithBrandColour(final String primaryColour) throws Exception {
    final String token = platformToken();
    final UUID organizationId = createOrganization(token);
    final JsonNode client = registerClient(token, organizationId);
    if (primaryColour != null) {
      setBranding(token, organizationId, client.get("id").asString(), primaryColour);
    }
    return new Fixture(organizationId, client.get("clientId").asString());
  }

  private String platformToken() throws IOException, InterruptedException {
    final String basic =
        Base64.getEncoder()
            .encodeToString("test-platform-client:a-test-platform-secret".getBytes());
    final HttpRequest request =
        HttpRequest.newBuilder(uri("/oauth2/token"))
            .header("Authorization", "Basic " + basic)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "grant_type=client_credentials&scope="
                        + URLEncoder.encode(
                            "platform:organizations:write platform:client-branding:write",
                            StandardCharsets.UTF_8)))
            .build();
    return objectMapper
        .readTree(httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body())
        .get("access_token")
        .asString();
  }

  private UUID createOrganization(final String token) throws IOException, InterruptedException {
    final PlatformAccount owner =
        PlatformAccount.register(new Email("owner-" + UUID.randomUUID() + "@example.test"));
    owner.attachPasswordCredential("not-a-real-hash-this-test-never-logs-in");
    platformAccounts.save(owner);
    final String body =
        "{\"name\":\"Brand Co\",\"ownerPlatformAccountId\":\"" + owner.id().value() + "\"}";
    return UUID.fromString(
        objectMapper
            .readTree(post(token, "/api/v1/admin/organizations", body).body())
            .get("id")
            .asString());
  }

  private JsonNode registerClient(final String token, final UUID organizationId)
      throws IOException, InterruptedException {
    final String body =
        "{\"redirectUris\":[\""
            + REDIRECT_URI
            + "\"],\"allowedGrantTypes\":[\"authorization_code\"],"
            + "\"allowedScopes\":[\"openid\"],\"requireConsent\":false}";
    return objectMapper.readTree(
        post(token, "/api/v1/admin/organizations/" + organizationId + "/clients", body).body());
  }

  private void setBranding(
      final String token,
      final UUID organizationId,
      final String oauthClientId,
      final String colour)
      throws IOException, InterruptedException {
    final HttpRequest request =
        HttpRequest.newBuilder(
                uri(
                    "/api/v1/admin/organizations/"
                        + organizationId
                        + "/clients/"
                        + oauthClientId
                        + "/branding"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .PUT(
                HttpRequest.BodyPublishers.ofString(
                    "{\"primaryColor\":\""
                        + colour
                        + "\",\"applicationDisplayName\":\"Brand App\"}"))
            .build();
    final HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
  }

  private HttpResponse<String> post(final String token, final String path, final String json)
      throws IOException, InterruptedException {
    final HttpRequest request =
        HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
    final HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isBetween(200, 201);
    return response;
  }

  private HttpResponse<String> get(final String path) throws IOException, InterruptedException {
    return httpClient.send(
        HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(final String path) {
    return URI.create("http://localhost:" + port + path);
  }
}
