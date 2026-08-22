package com.clavaris.identity.infrastructure.adapter.out.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.identity.domain.model.OrganizationId;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * TD-SEC-020: {@code ResendMailSender} is the only class in this codebase that makes a real
 * outbound call to a third-party API — before this test existed, that call had zero dedicated
 * coverage; its 16.7% incidental coverage came only from a test elsewhere once forgetting to mock
 * it (the same failure mode reproduced, then fixed, earlier in this same review). Two verification
 * strategies, matched to what each test actually needs to prove:
 *
 * <ul>
 *   <li>the happy path runs against a real local {@link HttpServer} (JDK built-in, no new
 *       dependency, no real network) — the only way to actually prove the request Resend would
 *       receive has the right method, headers, and JSON body, not just that no exception was thrown
 *   <li>every error path runs against a Mockito-mocked {@link HttpClient} — a non-2xx response, an
 *       {@link IOException}, and an {@link InterruptedException} are all trivial to force
 *       deterministically this way, and none of them need a real socket to prove
 * </ul>
 */
class ResendMailSenderTest {

  private static final String API_KEY = "test-resend-api-key";
  private static final String FROM_ADDRESS = "no-reply@clavaris.test";
  private static final String BASE_URL = "https://clavaris.example.test";

  private final ObjectMapper objectMapper = new ObjectMapper();

  private HttpServer stubServer;
  private volatile CapturedRequest capturedRequest;

  @BeforeEach
  void startStubServer() throws IOException {
    stubServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    stubServer.start();
  }

  @AfterEach
  void stopStubServer() {
    stubServer.stop(0);
  }

  @Test
  void sendsAWellFormedRequestForATenantAccountAndSucceedsOnA2xxResponse() {
    respondWith(200, "");
    ResendMailSender sender = senderPointedAtTheStubServer();
    UUID organizationId = UUID.randomUUID();

    sender.sendEmailVerification(
        "user@example.com", new OrganizationId(organizationId), "the-raw-token");

    assertThat(capturedRequest.method).isEqualTo("POST");
    assertThat(capturedRequest.headers)
        .containsEntry("Authorization", "Bearer " + API_KEY)
        .containsEntry("Content-Type", "application/json");
    JsonNode body = objectMapper.readTree(capturedRequest.body);
    assertThat(body.get("from").asString()).isEqualTo(FROM_ADDRESS);
    assertThat(body.get("to").get(0).asString()).isEqualTo("user@example.com");
    assertThat(body.get("subject").asString()).isEqualTo("Verify your email address");
    assertThat(body.get("html").asString())
        .as(
            "the tenant link must be organization-scoped (/o/{organizationId}/...), not the "
                + "platform tier's own /platform/... shape")
        .contains(BASE_URL + "/o/" + organizationId + "/verify-email?token=the-raw-token");
  }

  @Test
  void sendsAWellFormedRequestForAPlatformAccountWithThePlatformTierLinkShape() {
    respondWith(200, "");
    ResendMailSender sender = senderPointedAtTheStubServer();

    sender.sendPlatformAccountPasswordReset("founder@example.com", "the-raw-token");

    JsonNode body = objectMapper.readTree(capturedRequest.body);
    assertThat(body.get("subject").asString()).isEqualTo("Reset your password");
    assertThat(body.get("html").asString())
        .as("the platform tier's own link has no organizationId segment at all")
        .contains(BASE_URL + "/platform/reset-password?token=the-raw-token");
  }

  @Test
  void urlEncodesTheTokenInTheLinkItBuilds() {
    respondWith(200, "");
    ResendMailSender sender = senderPointedAtTheStubServer();

    sender.sendPlatformAccountEmailVerification(
        "founder@example.com", "a token/with+special=chars");

    JsonNode body = objectMapper.readTree(capturedRequest.body);
    assertThat(body.get("html").asString())
        .as("a raw token containing URL-significant characters must never reach the link unencoded")
        .contains("token=a+token%2Fwith%2Bspecial%3Dchars")
        .doesNotContain("token=a token/with+special=chars");
  }

  @Test
  void throwsMailDeliveryExceptionOnANon2xxResponse() {
    respondWith(422, "{\"message\":\"Invalid `from` field\"}");
    ResendMailSender sender = senderPointedAtTheStubServer();

    assertThatExceptionOfType(MailDeliveryException.class)
        .isThrownBy(() -> sender.sendPlatformAccountEmailVerification("founder@example.com", "tok"))
        .withMessageContaining("responded with status 422")
        .as(
            "BR-DATA-01: the response body (which never contains PII here, but easily could) must "
                + "never leak into the exception message — only the status code")
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("Invalid"));
  }

  @Test
  void throwsMailDeliveryExceptionWrappingAnIOException() throws IOException, InterruptedException {
    HttpClient httpClient = mock(HttpClient.class);
    IOException networkFailure = new IOException("connection reset");
    when(httpClient.send(any(), any())).thenThrow(networkFailure);
    ResendMailSender sender = senderWith(httpClient);

    assertThatExceptionOfType(MailDeliveryException.class)
        .isThrownBy(() -> sender.sendPlatformAccountEmailVerification("founder@example.com", "tok"))
        .withMessageContaining("network/IO")
        .withCause(networkFailure);
  }

  @Test
  void throwsMailDeliveryExceptionAndRestoresTheInterruptFlagOnInterruptedException()
      throws IOException, InterruptedException {
    HttpClient httpClient = mock(HttpClient.class);
    when(httpClient.send(any(), any())).thenThrow(new InterruptedException("interrupted mid-send"));
    ResendMailSender sender = senderWith(httpClient);

    try {
      assertThatExceptionOfType(MailDeliveryException.class)
          .isThrownBy(
              () -> sender.sendPlatformAccountEmailVerification("founder@example.com", "tok"))
          .withMessageContaining("interrupted");
      assertThat(Thread.interrupted())
          .as(
              "a caught InterruptedException must restore the interrupt flag, never swallow it — "
                  + "Thread.interrupted() both reads and clears it, so this must run exactly once")
          .isTrue();
    } finally {
      // Belt and suspenders: Thread.interrupted() above already cleared it on the assertion path,
      // but if that assertion itself failed first, clear it anyway so this test can't leave a
      // dangling interrupt flag for whichever test the JVM runs next on this same thread.
      Thread.interrupted();
    }
  }

  private ResendMailSender senderPointedAtTheStubServer() {
    return senderWith(HttpClient.newHttpClient());
  }

  private ResendMailSender senderWith(final HttpClient httpClient) {
    return new ResendMailSender(
        httpClient, objectMapper, API_KEY, FROM_ADDRESS, BASE_URL, stubServerUri());
  }

  private URI stubServerUri() {
    return URI.create("http://localhost:" + stubServer.getAddress().getPort() + "/emails");
  }

  private void respondWith(final int statusCode, final String responseBody) {
    stubServer.createContext(
        "/emails",
        exchange -> {
          capturedRequest = CapturedRequest.from(exchange);
          byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(statusCode, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
  }

  private record CapturedRequest(
      String method, java.util.Map<String, String> headers, String body) {

    private static CapturedRequest from(final HttpExchange exchange) throws IOException {
      String requestBody =
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      return new CapturedRequest(
          exchange.getRequestMethod(),
          java.util.Map.of(
              "Authorization",
              exchange.getRequestHeaders().getFirst("Authorization"),
              "Content-Type",
              exchange.getRequestHeaders().getFirst("Content-Type")),
          requestBody);
    }
  }
}
