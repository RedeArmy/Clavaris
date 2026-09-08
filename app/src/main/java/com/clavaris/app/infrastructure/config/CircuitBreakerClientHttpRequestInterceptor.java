package com.clavaris.app.infrastructure.config;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.common.infrastructure.adapter.out.resilience.CircuitBreakerMetricsBinder;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * SDE-III optimization pass, P2 point 4: same "fail fast on a dependency already known to be down"
 * circuit breaker {@code ResendHttpClient}/{@code GitHubVerifiedEmailUserService} already
 * establish, applied here to every call {@code SocialLoginConfig}'s own {@code RestClient}/{@code
 * RestOperations} beans make — Google's and GitHub's own token-exchange endpoints, plus Google's
 * own OIDC userinfo endpoint (GitHub's base {@code /user} userinfo call goes through the same
 * shared {@code RestOperations} bean too).
 *
 * <p><b>One {@link CircuitBreaker} per target host, created on first use, not one per bean.</b>
 * Google and GitHub are two genuinely independent failure domains — a Google outage must never trip
 * the same breaker GitHub's own calls share, and vice versa — but both this interceptor's own
 * consumer beans see traffic to more than one host (Google's token endpoint and GitHub's token
 * endpoint share the token-exchange {@code RestClient}; Google's userinfo endpoint and GitHub's
 * base {@code /user} share the userinfo {@code RestOperations}). Keying by {@link
 * HttpRequest#getURI()}'s own host, lazily, means this class needs no hardcoded knowledge of which
 * hosts a given deployment actually talks to — a future third social provider (Microsoft,
 * TD-FUT-022) is automatically its own isolated breaker the first time this interceptor ever sees a
 * request to its host, no code change needed here.
 */
// PMD.LongVariable: circuitBreakersByHost/circuitBreakerConfig name exactly what they are — same
// "deliberate, descriptive name over an arbitrary shortening" convention this codebase applies
// everywhere else this rule fires (e.g. RedisFixedWindowRateLimiter's own identical suppression).
@SuppressWarnings("PMD.LongVariable")
final class CircuitBreakerClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {

  private final ConcurrentMap<String, CircuitBreaker> circuitBreakersByHost =
      new ConcurrentHashMap<>();
  private final CircuitBreakerConfig circuitBreakerConfig;
  private final SecurityMetricsRecorder metrics;

  /* package */ CircuitBreakerClientHttpRequestInterceptor(
      final CircuitBreakerConfig circuitBreakerConfig, final SecurityMetricsRecorder metrics) {
    this.circuitBreakerConfig = circuitBreakerConfig;
    this.metrics = metrics;
  }

  // PMD.AvoidCatchingGenericException: the final catch (Exception e) is defensive-only, matching
  // ClientHttpRequestExecution#execute's own broad Callable#call signature executeCallable
  // propagates — same rationale ResendHttpClient#send's own identical catch documents.
  // PMD.LawOfDemeter: request.getURI() is the standard ClientHttpRequestInterceptor API shape for
  // reading the target host — there is no other way to reach it, same rationale
  // AntiAbuseRateLimitingFilter's own response.getWriter() suppression already documents.
  @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.LawOfDemeter"})
  @Override
  public ClientHttpResponse intercept(
      final HttpRequest request, final byte[] body, final ClientHttpRequestExecution execution)
      throws IOException {
    final String host = request.getURI().getHost();
    final CircuitBreaker circuitBreaker =
        circuitBreakersByHost.computeIfAbsent(host, this::newCircuitBreakerFor);
    try {
      return circuitBreaker.executeCallable(() -> execution.execute(request, body));
    } catch (final CallNotPermittedException e) {
      throw new IOException(
          "Circuit breaker open for " + host + " — this dependency appears to be down", e);
    } catch (final IOException e) {
      throw e;
    } catch (final Exception e) {
      // Unreachable in practice — ClientHttpRequestExecution#execute only ever throws IOException
      // itself; defensive only.
      throw new IOException("Unexpected failure calling " + host, e);
    }
  }

  private CircuitBreaker newCircuitBreakerFor(final String host) {
    final CircuitBreaker circuitBreaker = CircuitBreaker.of("oauth2-" + host, circuitBreakerConfig);
    CircuitBreakerMetricsBinder.bind(circuitBreaker, metrics);
    return circuitBreaker;
  }
}
