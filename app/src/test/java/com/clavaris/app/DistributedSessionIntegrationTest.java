package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.app.support.TestMailSenderConfig;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.PlatformMailSender;
import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisIndexedHttpSession;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * TD-ARCH-002 (closed): the architectural claim this row named — a session survives a restart and
 * is visible to more than one running instance — is only actually proven by reading it back from
 * somewhere that is provably NOT this test's own Spring context, the same "simulate a real restart,
 * not just re-reading the same object" bar TD-SEC-002's own signing-key test already set for this
 * codebase. A second, fully independent {@link AnnotationConfigApplicationContext}, wired only via
 * Spring Session's own {@code @EnableRedisIndexedHttpSession} and pointed at the identical
 * Testcontainers Redis instance, stands in for that second instance — not a bean this app's own
 * {@code @SpringBootTest} context already cached.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
class DistributedSessionIntegrationTest extends RedisBackedIntegrationTest {

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
  void aRealLoginSessionIsReadableFromRedisByACompletelyIndependentSecondSpringContext()
      throws Exception {
    String email = "distributed-session@example.com";
    String password = "the-original-password";
    registerPlatformAccount(email, password);
    ArgumentCaptor<String> verificationToken = ArgumentCaptor.forClass(String.class);
    verify(mailSender).sendPlatformAccountEmailVerification(eq(email), verificationToken.capture());
    getVerifyEmail(verificationToken.getValue());
    assertThat(login(email, password).statusCode()).isEqualTo(302);

    String sessionId = extractSessionCookieValue();
    UUID platformAccountId =
        jdbcTemplate.queryForObject(
            "select id from platform_accounts where email = ?", UUID.class, email);

    // Proof #1: the session really landed in Redis, under the exact namespace application.yml
    // configures (spring.session.redis.namespace) — not silently falling back to the servlet
    // container's own in-memory store despite the dependency/config both being present.
    // LettuceConnectionFactory is a DisposableBean, not AutoCloseable — destroy() is the
    // equivalent manual cleanup try-with-resources would otherwise give for free.
    final LettuceConnectionFactory rawConnectionFactory =
        new LettuceConnectionFactory(redisHost(), redisPort());
    rawConnectionFactory.afterPropertiesSet();
    try {
      StringRedisTemplate rawRedis = new StringRedisTemplate(rawConnectionFactory);
      assertThat(rawRedis.keys("clavaris:sessions:sessions:*"))
          .as("a real Redis hash key must exist for the session this login just created")
          .isNotEmpty();
    } finally {
      rawConnectionFactory.destroy();
    }

    // Proof #2: a second, independent process (simulated by a second Spring context, its own
    // RedisConnectionFactory, never sharing a bean with this test's own @SpringBootTest context)
    // can load that exact session by id and reconstruct who is authenticated on it — the actual
    // property TD-ARCH-002 was about (works across more than one running instance), not just
    // "some bytes exist in Redis."
    try (AnnotationConfigApplicationContext secondInstance =
        new AnnotationConfigApplicationContext(SecondInstanceSessionConfig.class)) {
      final FindByIndexNameSessionRepository<? extends Session> sessionRepository =
          secondInstance.getBean(FindByIndexNameSessionRepository.class);
      final Session session = sessionRepository.findById(sessionId);
      assertThat(session)
          .as(
              "the session established by this test's own HTTP client must be visible from a"
                  + " completely independent Spring context reading the same Redis keyspace")
          .isNotNull();

      final SecurityContext securityContext = session.getAttribute("SPRING_SECURITY_CONTEXT");
      assertThat(securityContext).isNotNull();
      assertThat(securityContext.getAuthentication().isAuthenticated()).isTrue();
      assertThat(securityContext.getAuthentication().getName())
          .as("the reconstructed principal must be the exact PlatformAccount that logged in")
          .isEqualTo(platformAccountId.toString());
      assertThat(securityContext.getAuthentication().getAuthorities())
          .extracting(Object::toString)
          .contains("ROLE_PLATFORM_ACCOUNT");
    }
  }

  // Mirrors DistributedSessionConfig's own real configuration exactly (redisNamespace), wired
  // independently via Spring Session's own annotation rather than reusing that class — a
  // genuinely separate assembly, pointed at the same Redis. @TestConfiguration, not plain
  // @Configuration: a nested @Configuration class on a @SpringBootTest test is auto-detected by
  // Spring Boot's test context loader and silently REPLACES the test's own ClavarisApplication
  // source (confirmed live — the very first attempt at this test failed at startup with "no
  // ServletWebServerFactory bean", because this nested class had hijacked THIS test's own
  // @SpringBootTest context instead of ClavarisApplication). @TestConfiguration is Spring Boot's
  // own documented opt-out of that auto-detection, while still being explicitly usable via
  // AnnotationConfigApplicationContext below.
  @TestConfiguration
  @EnableRedisIndexedHttpSession(redisNamespace = "clavaris:sessions")
  static class SecondInstanceSessionConfig {

    @Bean
    RedisConnectionFactory redisConnectionFactory() {
      return new LettuceConnectionFactory(redisHost(), redisPort());
    }
  }

  private String extractSessionCookieValue() {
    // Spring Session's own DefaultCookieSerializer names the session cookie "SESSION", not the
    // servlet container's usual "JSESSIONID", and Base64-encodes the actual session id by default
    // (useBase64Encoding=true, confirmed by reading its source directly after this test's own
    // first run failed here) — the raw cookie value is not the Redis key's own id, it must be
    // decoded first.
    String rawCookieValue =
        cookieManager.getCookieStore().getCookies().stream()
            .filter(cookie -> "SESSION".equalsIgnoreCase(cookie.getName()))
            .map(HttpCookie::getValue)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no SESSION cookie after a successful login"));
    return new String(Base64.getDecoder().decode(rawCookieValue), StandardCharsets.UTF_8);
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
