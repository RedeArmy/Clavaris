package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * DefaultSecurityConfig's own Javadoc: CSRF is deliberately left enabled (Spring Security's
 * default) for this hosted-UI chain, unlike the two Bearer-token chains — proves both directions
 * live, real HTTP, real session cookie, not an assumption that Spring Security's default "just
 * works": a POST with no {@code _csrf} token is rejected (403), and the exact same form submitted
 * with the token {@code register.html} actually renders succeeds (302 to pending-verification).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RegisterAccountCsrfIntegrationTest {

  private static final Pattern CSRF_TOKEN_PATTERN =
      Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  // A CookieManager, not a fresh HttpClient per request: the session cookie the GET response sets
  // (Spring Security's default HttpSessionCsrfTokenRepository ties the token to the session) must
  // travel with the follow-up POST, or the server sees a brand-new session with no matching token
  // at all — a different failure mode than the one this test means to prove.
  private final HttpClient httpClient =
      HttpClient.newBuilder()
          .cookieHandler(new CookieManager())
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  @Test
  void rejectsAFormSubmissionWithNoCsrfToken() throws Exception {
    UUID organizationId = UUID.randomUUID();
    fetchCsrfToken(organizationId); // establishes the session cookie the POST below reuses

    HttpResponse<String> response =
        submitRegisterForm(organizationId, null, "csrf-missing@example.com", "a-valid-password");

    assertThat(response.statusCode()).isEqualTo(403);
  }

  @Test
  void acceptsAFormSubmissionCarryingTheRealCsrfToken() throws Exception {
    UUID organizationId = UUID.randomUUID();
    String token = fetchCsrfToken(organizationId);

    HttpResponse<String> response =
        submitRegisterForm(organizationId, token, "csrf-present@example.com", "a-valid-password");

    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(response.headers().firstValue("Location").orElseThrow())
        .contains("pending-verification");
  }

  private String fetchCsrfToken(UUID organizationId) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/register")).GET().build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);

    Matcher matcher = CSRF_TOKEN_PATTERN.matcher(response.body());
    assertThat(matcher.find()).as("register.html must render a _csrf hidden input").isTrue();
    return matcher.group(1);
  }

  private HttpResponse<String> submitRegisterForm(
      UUID organizationId, String csrfTokenOrNull, String email, String password)
      throws IOException, InterruptedException {
    StringBuilder body = new StringBuilder();
    if (csrfTokenOrNull != null) {
      body.append("_csrf=").append(csrfTokenOrNull).append('&');
    }
    body.append("email=")
        .append(email)
        .append("&password=")
        .append(password)
        .append("&confirmPassword=")
        .append(password);

    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/o/" + organizationId + "/register"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private URI baseUri(String path) {
    return URI.create("http://localhost:" + port + path);
  }
}
