package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.app.support.TestMailSenderConfig;
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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * TD-SEC-085: end to end, on the real chain, that the sign-in steps that take a code or open a
 * challenge answer 429 once a source IP passes its limit. {@code
 * LoginEndpointsRateLimitCoverageTest} proves every endpoint has a rule; this proves the rules are
 * actually applied by the chain that serves those pages, real HTTP, real Redis, real Postgres.
 *
 * <p>The limits are lowered so each case is a handful of requests. The CSRF token is a real one,
 * because the filter that limits sits behind Spring's CSRF check: a request without it never
 * reaches the limit at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
@TestPropertySource(
    properties = {
      "clavaris.rate-limit.code-confirm.per-ip-limit=3",
      "clavaris.rate-limit.webauthn.per-ip-limit=3"
    })
class HostedPageLoginRateLimitIntegrationTest extends RedisBackedIntegrationTest {

  private static final Pattern CSRF_TOKEN_PATTERN =
      Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");
  private static final int LIMIT = 3;

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  private final HttpClient httpClient =
      HttpClient.newBuilder()
          .cookieHandler(new CookieManager())
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  // The authenticator-app challenge: the Account is in the session, so the only key is the IP.
  @Test
  void theAuthenticatorCodeEntryAnswers429PastThePerIpLimit() throws Exception {
    final UUID organization = UUID.randomUUID();
    final String csrf = csrfToken(organization);
    final String path = "/o/" + organization + "/login/totp";

    for (int attempt = 1; attempt <= LIMIT; attempt++) {
      assertThat(postForm(path, csrf, "code=000000").statusCode())
          .as("attempt %d is within the limit", attempt)
          .isNotEqualTo(429);
    }

    final HttpResponse<String> over = postForm(path, csrf, "code=000000");
    assertThat(over.statusCode()).isEqualTo(429);
    assertThat(over.headers().firstValue("Retry-After")).isPresent();
  }

  @Test
  void thePasskeySignInStartAnswers429PastItsOwnPerIpLimit() throws Exception {
    final UUID organization = UUID.randomUUID();
    final String csrf = csrfToken(organization);
    final String path = "/o/" + organization + "/login/webauthn/start";

    for (int attempt = 1; attempt <= LIMIT; attempt++) {
      assertThat(postJson(path, csrf).statusCode())
          .as("attempt %d is within the limit", attempt)
          .isNotEqualTo(429);
    }

    assertThat(postJson(path, csrf).statusCode()).isEqualTo(429);
  }

  // The steps after the primary factor; the password one asks the breached-password service.
  @Test
  void theSessionTaskStepsAnswer429PastThePerIpLimit() throws Exception {
    final UUID organization = UUID.randomUUID();
    final String csrf = csrfToken(organization);
    final String path = "/o/" + organization + "/login/session-task/password-reset";

    for (int attempt = 1; attempt <= LIMIT; attempt++) {
      assertThat(postForm(path, csrf, "password=x").statusCode())
          .as("attempt %d is within the limit", attempt)
          .isNotEqualTo(429);
    }

    assertThat(postForm(path, csrf, "password=x").statusCode()).isEqualTo(429);
  }

  private String csrfToken(final UUID organization) throws IOException, InterruptedException {
    final HttpResponse<String> page =
        httpClient.send(
            HttpRequest.newBuilder(uri("/o/" + organization + "/login")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    final Matcher matcher = CSRF_TOKEN_PATTERN.matcher(page.body());
    assertThat(matcher.find()).as("the sign-in page must render a _csrf input").isTrue();
    return matcher.group(1);
  }

  private HttpResponse<String> postForm(final String path, final String csrf, final String fields)
      throws IOException, InterruptedException {
    return httpClient.send(
        HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + csrf + "&" + fields))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> postJson(final String path, final String csrf)
      throws IOException, InterruptedException {
    return httpClient.send(
        HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/json")
            .header("X-CSRF-TOKEN", csrf)
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(final String path) {
    return URI.create("http://localhost:" + port + path);
  }
}
