package com.clavaris.identity.infrastructure.adapter.out.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.requestemailverification.MailDeliveryException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * SDE-III optimization pass, P2 point 4: dedicated coverage for the property {@link
 * ResendMailSenderTest} can't practically exercise — {@code CircuitBreaker.ofDefaults} (what the
 * test-only {@code ResendMailSender} constructor builds) needs 100 calls in its sliding window
 * before it can ever trip, far too many for a fast unit test. This class constructs {@link
 * ResendHttpClient} directly (same package) with a deliberately small, test-friendly window
 * instead, to prove the circuit genuinely opens and genuinely fails fast — not just that the code
 * compiles against the right exception type.
 */
class ResendHttpClientTest {

  private static final URI ENDPOINT = URI.create("http://localhost:1/emails");

  @Test
  void tripsOpenAfterRepeatedFailuresAndThenFailsFastWithoutCallingTheRealHttpClientAgain()
      throws IOException, InterruptedException {
    HttpClient httpClient = mock(HttpClient.class);
    when(httpClient.send(any(), any())).thenThrow(new IOException("connection refused"));
    CircuitBreaker circuitBreaker =
        CircuitBreaker.of(
            "resend-test",
            CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(5))
                .build());
    ResendHttpClient client =
        new ResendHttpClient(
            httpClient, new ObjectMapper(), "test-key", "no-reply@test", ENDPOINT, circuitBreaker);

    // Two failures fill the 2-call sliding window and exceed the 50% failure-rate threshold —
    // the circuit is now OPEN. Both still reach the real (mocked) HttpClient — the breaker only
    // starts rejecting calls itself once it has enough data to open.
    assertThatExceptionOfType(MailDeliveryException.class)
        .isThrownBy(() -> client.send("user@example.com", "subject", "html"))
        .withMessageContaining("network/IO");
    assertThatExceptionOfType(MailDeliveryException.class)
        .isThrownBy(() -> client.send("user@example.com", "subject", "html"))
        .withMessageContaining("network/IO");
    assertThat(circuitBreaker.getState())
        .as("2 failures out of a 2-call window at a 50% threshold must open the circuit")
        .isEqualTo(CircuitBreaker.State.OPEN);

    // The third call must fail immediately, with the circuit-breaker-specific message — and,
    // decisively, without ever reaching the real HttpClient again.
    assertThatExceptionOfType(MailDeliveryException.class)
        .isThrownBy(() -> client.send("user@example.com", "subject", "html"))
        .withMessageContaining("circuit breaker is open");
    verify(httpClient, times(2)).send(any(), any());
  }

  @Test
  void aHealthyDependencyNeverTripsTheCircuitAndEveryCallReachesTheRealHttpClient()
      throws IOException, InterruptedException {
    HttpClient httpClient = mock(HttpClient.class);
    @SuppressWarnings("unchecked")
    HttpResponse<String> successResponse = mock(HttpResponse.class);
    when(successResponse.statusCode()).thenReturn(200);
    doReturn(successResponse).when(httpClient).send(any(), any());
    CircuitBreaker circuitBreaker =
        CircuitBreaker.of(
            "resend-test-healthy",
            CircuitBreakerConfig.custom().slidingWindowSize(2).minimumNumberOfCalls(2).build());
    ResendHttpClient client =
        new ResendHttpClient(
            httpClient, new ObjectMapper(), "test-key", "no-reply@test", ENDPOINT, circuitBreaker);

    client.send("user@example.com", "subject", "html");
    client.send("user@example.com", "subject", "html");
    client.send("user@example.com", "subject", "html");

    assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    verify(httpClient, times(3)).send(any(), any());
  }
}
