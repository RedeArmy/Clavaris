package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * TD-ARCH-056: the application starts against a Redis nobody listens on. Spring Session used to run
 * {@code CONFIG SET notify-keyspace-events} while the context started and a failure there ended the
 * process, so a restart or a rolling deploy that landed while Redis restarted took the identity
 * provider down for every consumer. Deliberately does not extend {@code
 * RedisBackedIntegrationTest}: that class exists to provide a Redis.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
// BR-ID-33: a dependency outage degrades readiness, it never prevents start-up.
class RedisUnreachableStartupIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @DynamicPropertySource
  static void redisNobodyListensOn(final DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", () -> "127.0.0.1");
    // Port 1 is reserved (tcpmux) and refuses connections on a test host.
    registry.add("spring.data.redis.port", () -> 1);
    registry.add("clavaris.webhook.dispatch-initial-delay-ms", () -> 31_536_000_000L);
    registry.add("clavaris.webhook.delivery-initial-delay-ms", () -> 31_536_000_000L);
  }

  @Value("${local.server.port}")
  private int port;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void theContextStartsAndLivenessIsUpWhileReadinessReportsTheOutage() throws Exception {
    assertThat(statusOf("/actuator/health/liveness"))
        .as("the process is alive without Redis")
        .isEqualTo(200);
    assertThat(statusOf("/actuator/health/readiness"))
        .as("readiness reflects the missing dependency, it does not hide it")
        .isEqualTo(503);
  }

  private int statusOf(final String path) throws Exception {
    final HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    return response.statusCode();
  }
}
