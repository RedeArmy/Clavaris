package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * TD-FUT-017: the real end-to-end proof its own row named as a deliberate, named gap left for a
 * follow-up — {@link
 * com.clavaris.app.infrastructure.adapter.out.security.Argon2BulkheadPasswordEncoder}'s own Javadoc
 * verified the OAuth2 {@code server_error} response shape by decompiling the resolved {@code
 * spring-security-oauth2-authorization-server} jar via {@code javap}, never by an actual saturated
 * {@code /oauth2/token} round trip. This class is that round trip: real Postgres/Redis (via {@link
 * RedisBackedIntegrationTest}), a real embedded server, real Argon2id client-secret verification —
 * no mocked gate, no mocked encoder.
 *
 * <p>{@code clavaris.security.argon2.max-concurrent-verifications=1}/{@code max-wait-millis=10}
 * (test-only override — production sizes to core count, see {@code
 * SemaphoreCpuBoundVerificationGate}'s own Javadoc) make saturation deterministic without a
 * timing-sensitive sleep: with only one permit ever available and a 10ms wait bound, firing several
 * genuinely concurrent requests against the same client credentials guarantees every request queued
 * behind whichever one wins the single permit first will still be mid-Argon2id-hash (measured p95
 * in the tens-to-hundreds of milliseconds, `load-testing/README.md` §4) well past that 10ms bound.
 * {@code PLATFORM_BOOTSTRAP_CLIENT_ID}'s own {@code client_credentials} grant is the exact call
 * site `Argon2ClientAuthenticationSupport`'s own Javadoc names as what `load-testing/README.md` §2
 * originally measured — not the interactive password-login path, a different bulkhead call site
 * with its own existing coverage ({@code Argon2PasswordVerifierTest}).
 *
 * <p>8 concurrent requests, comfortably under {@code oauth2.rate-limit.token.per-client-limit}
 * (20/5min) so the anti-abuse limiter never masks this test's own signal with an unrelated 429.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret",
      "clavaris.security.argon2.max-concurrent-verifications=1",
      "clavaris.security.argon2.max-wait-millis=10"
    })
class Argon2TokenEndpointSaturationIntegrationTest extends RedisBackedIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  private static final int CONCURRENT_REQUESTS = 8;
  private static final String BASIC_AUTH =
      "Basic "
          + Base64.getEncoder()
              .encodeToString("test-platform-client:a-test-platform-secret".getBytes());

  @Value("${local.server.port}")
  private int port;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Test
  void aSaturatedGateRejectsSomeConcurrentTokenRequestsWithARealOAuth2ServerErrorResponse()
      throws Exception {
    final List<HttpResponse<String>> responses = new CopyOnWriteArrayList<>();
    final ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
    final CountDownLatch startGate = new CountDownLatch(1);
    final CountDownLatch doneLatch = new CountDownLatch(CONCURRENT_REQUESTS);

    try {
      for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
        pool.submit(
            () -> {
              try {
                startGate.await();
                responses.add(requestToken());
              } catch (final InterruptedException _) {
                Thread.currentThread().interrupt();
              } catch (final IOException e) {
                throw new IllegalStateException(e);
              } finally {
                doneLatch.countDown();
              }
            });
      }

      // Every task is queued and waiting on the same gate before any of them actually fires —
      // this is what makes the requests genuinely overlap in flight, not just "eventually all
      // run one at a time on the pool" — same pattern PerTenantConcurrencyIntegrationTest already
      // establishes for an analogous concurrency proof.
      startGate.countDown();
      final boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
      assertThat(completed)
          .as("all %d concurrent requests completed in time", CONCURRENT_REQUESTS)
          .isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(responses).hasSize(CONCURRENT_REQUESTS);

    final List<HttpResponse<String>> succeeded =
        responses.stream()
            .filter(r -> r.statusCode() == 200 && r.body().contains("access_token"))
            .toList();
    final List<HttpResponse<String>> overloaded =
        responses.stream()
            .filter(r -> r.statusCode() != 200 && r.body().contains("\"error\":\"server_error\""))
            .toList();

    assertThat(succeeded)
        .as(
            "at least one of %d concurrent requests must still succeed — the gate serializes,"
                + " it never blocks every caller outright",
            CONCURRENT_REQUESTS)
        .isNotEmpty();
    assertThat(overloaded)
        .as(
            "at least one of %d concurrent requests, all sharing a single Argon2 verification"
                + " permit with a 10ms wait bound, must be rejected with a real OAuth2"
                + " server_error response — not admitted to queue indefinitely, and not a raw"
                + " 500/exception. Actual responses: %s",
            CONCURRENT_REQUESTS,
            responses.stream().map(r -> r.statusCode() + ":" + r.body()).toList())
        .isNotEmpty();
  }

  private HttpResponse<String> requestToken() throws IOException, InterruptedException {
    final HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/oauth2/token"))
            .header("Authorization", BASIC_AUTH)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "grant_type=client_credentials&scope=platform:organizations:write"))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
