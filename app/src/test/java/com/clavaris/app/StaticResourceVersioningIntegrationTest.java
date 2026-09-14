package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * TD-PERF-024 (content-hash revision, 2026-09-14): end to end against a real running server, real
 * Thymeleaf rendering, and Spring's own real static-resource-handling machinery — proves {@code
 * FilterOrderingConfig#resourceUrlEncodingFilter} and {@code application.yml}'s {@code
 * spring.web.resources.chain.strategy.content} config genuinely rewrite a real page's {@code
 * th:href="@{/css/clavaris.css}"} link to a content-hashed URL, that the hashed URL actually serves
 * the stylesheet with a long, effectively-immutable {@code Cache-Control}, and that the original
 * bare path still resolves too (so nothing already linking the old URL 404s). {@code
 * /platform/login} is this codebase's own simplest public, unauthenticated page that renders the
 * shared {@code head.html} fragment — no session, no CSRF token, no mocked collaborator needed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class StaticResourceVersioningIntegrationTest extends RedisBackedIntegrationTest {

  // No @ServiceConnection Redis here — RedisBackedIntegrationTest's own @Import already wires
  // one via @DynamicPropertySource, same as every other class extending it.
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  private static final Pattern HASHED_CSS_HREF_PATTERN =
      Pattern.compile("href=\"(/css/clavaris-[0-9a-f]+\\.css)\"");

  @Value("${local.server.port}")
  private int port;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Test
  void theRenderedLoginPageLinksAContentHashedStylesheetUrl()
      throws IOException, InterruptedException {
    HttpResponse<String> response = get("/platform/login");

    assertThat(response.statusCode()).isEqualTo(200);
    Matcher matcher = HASHED_CSS_HREF_PATTERN.matcher(response.body());
    assertThat(matcher.find())
        .as("expected a content-hashed /css/clavaris-<hash>.css link in the rendered page")
        .isTrue();
  }

  @Test
  void theContentHashedStylesheetUrlServesTheRealCssWithALongImmutableCacheControl()
      throws IOException, InterruptedException {
    String hashedPath = extractHashedCssPath(get("/platform/login").body());

    HttpResponse<String> response = get(hashedPath);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("--clavaris-"); // a real clavaris.css custom property
    String cacheControl = response.headers().firstValue("Cache-Control").orElse("");
    assertThat(cacheControl).contains("max-age=31536000").contains("public");
  }

  // Backward compatibility: VersionResourceResolver strips a recognized version segment before
  // delegating to the next resolver, but a request with NO version segment at all must still fall
  // through to the plain PathResourceResolver unchanged — anything that already has the bare path
  // cached/bookmarked/hardcoded must never start 404ing the moment this feature ships.
  @Test
  void theOriginalUnhashedStylesheetPathStillResolves() throws IOException, InterruptedException {
    HttpResponse<String> response = get("/css/clavaris.css");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("--clavaris-");
  }

  private static String extractHashedCssPath(final String html) {
    Matcher matcher = HASHED_CSS_HREF_PATTERN.matcher(html);
    if (!matcher.find()) {
      throw new IllegalStateException("No content-hashed CSS link found in: " + html);
    }
    return matcher.group(1);
  }

  private HttpResponse<String> get(final String path) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
