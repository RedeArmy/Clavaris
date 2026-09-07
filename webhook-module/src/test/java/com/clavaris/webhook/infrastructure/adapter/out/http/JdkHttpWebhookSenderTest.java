package com.clavaris.webhook.infrastructure.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.webhook.application.usecase.deliverpendingwebhooks.WebhookDeliveryOutcome;
import com.clavaris.webhook.infrastructure.adapter.out.security.SsrfCheckResult;
import com.clavaris.webhook.infrastructure.adapter.out.security.WebhookUrlSsrfChecker;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Real HTTP round-trips against a plain {@code com.sun.net.httpserver.HttpServer} — same "no new
 * dependency, JDK is enough" choice {@link JdkHttpWebhookSender} itself makes for the client side,
 * so the test doesn't need one either.
 *
 * <p>TD-SEC-053: every test server here binds to {@code localhost} — a loopback address the real
 * {@link WebhookUrlSsrfChecker} would (correctly) block. Every test unrelated to the SSRF guard
 * itself uses {@link #ALLOW_ALL}, a stub that always reports safe, keeping those tests focused on
 * HTTP behaviour; the guard's own blocking behaviour is proven separately below using the real
 * checker.
 */
class JdkHttpWebhookSenderTest {

  private static final WebhookUrlSsrfChecker ALLOW_ALL =
      new WebhookUrlSsrfChecker() {
        @Override
        public SsrfCheckResult check(final String url) {
          return SsrfCheckResult.SAFE;
        }
      };

  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  private String startServerRespondingWith(final int statusCode) throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/hook",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(statusCode, -1);
          exchange.close();
        });
    server.start();
    return "http://localhost:" + server.getAddress().getPort() + "/hook";
  }

  @Test
  void treatsA2xxResponseAsSuccessAndCapturesTheStatusCode() throws IOException {
    String url = startServerRespondingWith(200);
    JdkHttpWebhookSender sender = new JdkHttpWebhookSender(5, ALLOW_ALL);

    WebhookDeliveryOutcome outcome =
        sender.send(url, Map.of("Clavaris-Signature", "t=1,v1=abc"), "{\"event\":\"x\"}");

    assertThat(outcome.success()).isTrue();
    assertThat(outcome.statusCode()).isEqualTo(200);
    assertThat(outcome.errorMessage()).isNull();
  }

  @Test
  void treatsA500ResponseAsFailureWithoutLeakingTheResponseBody() throws IOException {
    String url = startServerRespondingWith(500);
    JdkHttpWebhookSender sender = new JdkHttpWebhookSender(5, ALLOW_ALL);

    WebhookDeliveryOutcome outcome = sender.send(url, Map.of(), "{}");

    assertThat(outcome.success()).isFalse();
    assertThat(outcome.statusCode()).isEqualTo(500);
    assertThat(outcome.errorMessage()).contains("500");
  }

  @Test
  void sendsEveryGivenHeader() throws IOException {
    AtomicReference<String> observedHeader = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/hook",
        exchange -> {
          observedHeader.set(exchange.getRequestHeaders().getFirst("Clavaris-Signature"));
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    String url = "http://localhost:" + server.getAddress().getPort() + "/hook";
    JdkHttpWebhookSender sender = new JdkHttpWebhookSender(5, ALLOW_ALL);

    sender.send(url, Map.of("Clavaris-Signature", "t=1,v1=abc"), "{}");

    assertThat(observedHeader.get()).isEqualTo("t=1,v1=abc");
  }

  @Test
  void treatsAnUnreachableHostAsAFailureRatherThanThrowing() {
    JdkHttpWebhookSender sender = new JdkHttpWebhookSender(1, ALLOW_ALL);

    WebhookDeliveryOutcome outcome = sender.send("http://127.0.0.1:1", Map.of(), "{}");

    assertThat(outcome.success()).isFalse();
    assertThat(outcome.statusCode()).isNull();
    assertThat(outcome.errorMessage()).contains("network/IO failure");
  }

  @Test
  void neverFollowsARedirect_soAConsumerCannotSmuggleADeliveryToAnUnregisteredUrl()
      throws IOException {
    // one server plays both roles: /hook redirects, /elsewhere is the URL this Organization never
    // actually registered.
    AtomicReference<Boolean> elsewhereWasHit = new AtomicReference<>(false);
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/hook",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          exchange
              .getResponseHeaders()
              .add(
                  "Location",
                  "http://localhost:" + exchange.getLocalAddress().getPort() + "/elsewhere");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/elsewhere",
        exchange -> {
          elsewhereWasHit.set(true);
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    String url = "http://localhost:" + server.getAddress().getPort() + "/hook";
    JdkHttpWebhookSender sender = new JdkHttpWebhookSender(5, ALLOW_ALL);

    WebhookDeliveryOutcome outcome = sender.send(url, Map.of(), "{}");

    assertThat(elsewhereWasHit.get()).isFalse();
    assertThat(outcome.success()).isFalse();
    assertThat(outcome.statusCode()).isEqualTo(302);
  }

  @Test
  void blocksALoopbackUrlWithoutEverConnecting() throws IOException {
    // TD-SEC-053: the real checker, not ALLOW_ALL — the server below proves the connection was
    // never even attempted, not just that the outcome happens to look like a failure.
    AtomicReference<Boolean> hookWasHit = new AtomicReference<>(false);
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/hook",
        exchange -> {
          hookWasHit.set(true);
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    JdkHttpWebhookSender sender = new JdkHttpWebhookSender(5, new WebhookUrlSsrfChecker());

    WebhookDeliveryOutcome outcome = sender.send(url, Map.of(), "{}");

    assertThat(hookWasHit.get()).isFalse();
    assertThat(outcome.success()).isFalse();
    assertThat(outcome.statusCode()).isNull();
    assertThat(outcome.errorMessage()).contains("blocked by SSRF guard").contains("loopback");
  }

  @Test
  void blocksACloudMetadataAddressWithoutEverConnecting() {
    // 169.254.169.254 — the well-known AWS/GCP/Azure instance-metadata endpoint, the single most
    // concrete real-world consequence of this finding (TD-SEC-053's own register wording).
    JdkHttpWebhookSender sender = new JdkHttpWebhookSender(5, new WebhookUrlSsrfChecker());

    WebhookDeliveryOutcome outcome =
        sender.send("https://169.254.169.254/latest/meta-data/", Map.of(), "{}");

    assertThat(outcome.success()).isFalse();
    assertThat(outcome.errorMessage()).contains("blocked by SSRF guard").contains("link-local");
  }
}
