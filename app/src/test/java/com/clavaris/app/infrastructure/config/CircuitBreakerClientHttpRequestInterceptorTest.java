package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Unit-level coverage of the per-host circuit-breaking/routing logic itself, against a mocked
 * {@link ClientHttpRequestExecution} — same split {@code AntiAbuseRateLimitingFilterTest} already
 * establishes against its own {@link RateLimiter}, and {@code ResendHttpClientTest} against {@code
 * ResendHttpClient}'s own circuit breaker.
 */
class CircuitBreakerClientHttpRequestInterceptorTest {

  private static final SecurityMetricsRecorder NO_OP_METRICS = (name, tags) -> {};
  private static final byte[] EMPTY_BODY = new byte[0];

  private static CircuitBreakerConfig testConfig() {
    return CircuitBreakerConfig.custom()
        .slidingWindowSize(2)
        .minimumNumberOfCalls(2)
        .failureRateThreshold(50)
        .waitDurationInOpenState(Duration.ofMinutes(5))
        .build();
  }

  @Test
  void tripsOpenAfterRepeatedFailuresAndThenFailsFastWithoutCallingExecutionAgain()
      throws IOException {
    ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
    when(execution.execute(any(), any())).thenThrow(new IOException("connection refused"));
    CircuitBreakerClientHttpRequestInterceptor interceptor =
        new CircuitBreakerClientHttpRequestInterceptor(testConfig(), NO_OP_METRICS);
    HttpRequest request = requestTo("https://api.github.com/login/oauth/access_token");

    assertThatExceptionOfType(IOException.class)
        .isThrownBy(() -> interceptor.intercept(request, EMPTY_BODY, execution));
    assertThatExceptionOfType(IOException.class)
        .isThrownBy(() -> interceptor.intercept(request, EMPTY_BODY, execution));

    // The third call must fail immediately, with the circuit-breaker-specific message — and,
    // decisively, without ever reaching the real execution again.
    assertThatExceptionOfType(IOException.class)
        .isThrownBy(() -> interceptor.intercept(request, EMPTY_BODY, execution))
        .withMessageContaining("Circuit breaker open");
    verify(execution, times(2)).execute(any(), any());
  }

  @Test
  void isolatesFailuresPerHostSoOneProvidersOutageNeverTripsAnotherProvidersCircuit()
      throws IOException {
    ClientHttpRequestExecution failingExecution = mock(ClientHttpRequestExecution.class);
    when(failingExecution.execute(any(), any())).thenThrow(new IOException("github is down"));
    ClientHttpRequestExecution healthyExecution = mock(ClientHttpRequestExecution.class);
    ClientHttpResponse successResponse = mock(ClientHttpResponse.class);
    when(healthyExecution.execute(any(), any())).thenReturn(successResponse);
    CircuitBreakerClientHttpRequestInterceptor interceptor =
        new CircuitBreakerClientHttpRequestInterceptor(testConfig(), NO_OP_METRICS);
    HttpRequest githubRequest = requestTo("https://api.github.com/login/oauth/access_token");
    HttpRequest googleRequest = requestTo("https://oauth2.googleapis.com/token");

    // Trip GitHub's own breaker open.
    assertThatExceptionOfType(IOException.class)
        .isThrownBy(() -> interceptor.intercept(githubRequest, EMPTY_BODY, failingExecution));
    assertThatExceptionOfType(IOException.class)
        .isThrownBy(() -> interceptor.intercept(githubRequest, EMPTY_BODY, failingExecution));

    // Google's own, separately-keyed breaker must be entirely unaffected — this call must reach
    // the real execution and succeed.
    ClientHttpResponse actual = interceptor.intercept(googleRequest, EMPTY_BODY, healthyExecution);

    assertThat(actual).isSameAs(successResponse);
    verify(healthyExecution).execute(any(), any());
  }

  @Test
  void aHealthyDependencyNeverTripsTheCircuitAndEveryCallReachesExecution() throws IOException {
    ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
    ClientHttpResponse successResponse = mock(ClientHttpResponse.class);
    when(execution.execute(any(), any())).thenReturn(successResponse);
    CircuitBreakerClientHttpRequestInterceptor interceptor =
        new CircuitBreakerClientHttpRequestInterceptor(testConfig(), NO_OP_METRICS);
    HttpRequest request = requestTo("https://oauth2.googleapis.com/token");

    interceptor.intercept(request, EMPTY_BODY, execution);
    interceptor.intercept(request, EMPTY_BODY, execution);
    interceptor.intercept(request, EMPTY_BODY, execution);

    verify(execution, times(3)).execute(any(), any());
  }

  private static HttpRequest requestTo(final String uri) {
    HttpRequest request = mock(HttpRequest.class);
    when(request.getURI()).thenReturn(URI.create(uri));
    return request;
  }
}
