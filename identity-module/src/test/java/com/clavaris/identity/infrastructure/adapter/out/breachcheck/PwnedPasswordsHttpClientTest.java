package com.clavaris.identity.infrastructure.adapter.out.breachcheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Same rationale {@code ResendHttpClientTest}'s own Javadoc documents for its sibling class: a
 * test-friendly, small sliding window proves the circuit breaker genuinely opens and genuinely
 * fails fast, not just that the code compiles against the right exception type. {@link
 * PwnedPasswordsBreachedPasswordCheckerTest} mocks this class entirely, so this is the only place
 * {@code lookupRange}'s own real HTTP/circuit-breaker mechanics — and both {@link
 * PwnedPasswordsLookupException} constructors — are actually exercised.
 */
class PwnedPasswordsHttpClientTest {

  private static final URI ENDPOINT = URI.create("https://api.pwnedpasswords.com/");

  private static CircuitBreaker newCircuitBreaker(final String name) {
    return CircuitBreaker.of(
        name,
        CircuitBreakerConfig.custom()
            .slidingWindowSize(2)
            .minimumNumberOfCalls(2)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofMinutes(5))
            .build());
  }

  @SuppressWarnings("unchecked")
  private static HttpResponse<String> responseWith(final int statusCode, final String body) {
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(statusCode);
    when(response.body()).thenReturn(body);
    return response;
  }

  @Test
  void aSuccessfulResponseReturnsItsBodyAsLines() throws IOException, InterruptedException {
    HttpClient httpClient = mock(HttpClient.class);
    doReturn(responseWith(200, "1E4C9B93F3F0682250B6CF8331B7EE68FD8:3\nABCDEF:1"))
        .when(httpClient)
        .send(any(), any());
    PwnedPasswordsHttpClient client =
        new PwnedPasswordsHttpClient(httpClient, ENDPOINT, newCircuitBreaker("pwned-success"));

    List<String> lines = client.lookupRange("5BAA6");

    assertThat(lines).containsExactly("1E4C9B93F3F0682250B6CF8331B7EE68FD8:3", "ABCDEF:1");
  }

  @Test
  void aServerErrorStatusThrowsWithoutACause() throws IOException, InterruptedException {
    HttpClient httpClient = mock(HttpClient.class);
    doReturn(responseWith(503, "")).when(httpClient).send(any(), any());
    PwnedPasswordsHttpClient client =
        new PwnedPasswordsHttpClient(httpClient, ENDPOINT, newCircuitBreaker("pwned-5xx"));

    assertThatExceptionOfType(PwnedPasswordsLookupException.class)
        .isThrownBy(() -> client.lookupRange("5BAA6"))
        .withMessageContaining("503")
        .withNoCause();
  }

  @Test
  void anIoExceptionIsWrappedWithTheRealExceptionAsCause()
      throws IOException, InterruptedException {
    HttpClient httpClient = mock(HttpClient.class);
    IOException networkFailure = new IOException("connection refused");
    when(httpClient.send(any(), any())).thenThrow(networkFailure);
    PwnedPasswordsHttpClient client =
        new PwnedPasswordsHttpClient(httpClient, ENDPOINT, newCircuitBreaker("pwned-io"));

    assertThatExceptionOfType(PwnedPasswordsLookupException.class)
        .isThrownBy(() -> client.lookupRange("5BAA6"))
        .withMessageContaining("network/IO")
        .withCause(networkFailure);
  }

  @Test
  void anInterruptedExceptionIsWrappedAndRestoresTheThreadsInterruptFlag()
      throws IOException, InterruptedException {
    HttpClient httpClient = mock(HttpClient.class);
    when(httpClient.send(any(), any())).thenThrow(new InterruptedException("interrupted"));
    PwnedPasswordsHttpClient client =
        new PwnedPasswordsHttpClient(httpClient, ENDPOINT, newCircuitBreaker("pwned-interrupt"));

    try {
      assertThatExceptionOfType(PwnedPasswordsLookupException.class)
          .isThrownBy(() -> client.lookupRange("5BAA6"))
          .withMessageContaining("interrupted");
      assertThat(Thread.currentThread().isInterrupted())
          .as("the catch block must restore the interrupt flag it consumed")
          .isTrue();
    } finally {
      // Clear the flag this test deliberately set, so it can't bleed into whatever test runs
      // next on the same (possibly pooled) JUnit worker thread.
      Thread.interrupted();
    }
  }

  @Test
  void anUnexpectedRuntimeExceptionIsStillWrapped() throws IOException, InterruptedException {
    HttpClient httpClient = mock(HttpClient.class);
    when(httpClient.send(any(), any())).thenThrow(new IllegalStateException("boom"));
    PwnedPasswordsHttpClient client =
        new PwnedPasswordsHttpClient(httpClient, ENDPOINT, newCircuitBreaker("pwned-unexpected"));

    assertThatExceptionOfType(PwnedPasswordsLookupException.class)
        .isThrownBy(() -> client.lookupRange("5BAA6"))
        .withMessageContaining("unexpectedly");
  }

  @Test
  void tripsOpenAfterRepeatedFailuresAndThenFailsFastWithoutCallingTheRealHttpClientAgain()
      throws IOException, InterruptedException {
    HttpClient httpClient = mock(HttpClient.class);
    when(httpClient.send(any(), any())).thenThrow(new IOException("connection refused"));
    CircuitBreaker circuitBreaker = newCircuitBreaker("pwned-breaker");
    PwnedPasswordsHttpClient client =
        new PwnedPasswordsHttpClient(httpClient, ENDPOINT, circuitBreaker);

    assertThatExceptionOfType(PwnedPasswordsLookupException.class)
        .isThrownBy(() -> client.lookupRange("5BAA6"))
        .withMessageContaining("network/IO");
    assertThatExceptionOfType(PwnedPasswordsLookupException.class)
        .isThrownBy(() -> client.lookupRange("5BAA6"))
        .withMessageContaining("network/IO");
    assertThat(circuitBreaker.getState())
        .as("2 failures out of a 2-call window at a 50% threshold must open the circuit")
        .isEqualTo(CircuitBreaker.State.OPEN);

    assertThatExceptionOfType(PwnedPasswordsLookupException.class)
        .isThrownBy(() -> client.lookupRange("5BAA6"))
        .withMessageContaining("circuit breaker is open")
        .withCauseInstanceOf(CallNotPermittedException.class);
    verify(httpClient, times(2)).send(any(), any());
  }
}
