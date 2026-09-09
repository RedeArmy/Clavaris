package com.clavaris.common.infrastructure.adapter.out.resilience;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * SDE-III optimization pass, P2 point 4: the shared "bind a {@link CircuitBreaker}'s own event
 * publisher to a real {@link SecurityMetricsRecorder} metric" wiring every genuinely external HTTP
 * dependency this codebase calls needs — Resend (identity-module), GitHub's REST API, and Google's/
 * GitHub's own OAuth2 endpoints (app). Same "every reliability-relevant decision gets a real,
 * alertable metric, not just a log line depending on someone watching it" discipline TD-FUT-011
 * already established for rate-limiting decisions ({@code RedisFixedWindowRateLimiter}'s own
 * identical shape) — a circuit breaker tripping open is exactly that kind of decision.
 *
 * <p>Deliberately a plain static utility, not a {@code @Component}: each consumer builds and tunes
 * its own {@link CircuitBreaker} instance directly (a different failure-rate threshold/wait
 * duration per dependency is a real, deliberate difference, not something one shared bean should
 * paper over) — this class only supplies the one genuinely identical piece, the metrics wiring
 * itself.
 */
public final class CircuitBreakerMetricsBinder {

  @SuppressWarnings("PMD.LongVariable")
  private static final String STATE_TRANSITION_METRIC = "clavaris.circuit_breaker.state_transition";

  @SuppressWarnings("PMD.LongVariable")
  private static final String CALL_NOT_PERMITTED_METRIC =
      "clavaris.circuit_breaker.call_not_permitted";

  private CircuitBreakerMetricsBinder() {}

  /**
   * Subscribes to {@code circuitBreaker}'s own event publisher for the rest of its lifetime — call
   * once, right after construction, same "wire it once, at startup" convention every other
   * long-lived collaborator in this codebase already follows.
   */
  // PMD.LawOfDemeter: circuitBreaker.getEventPublisher().onXxx(...) is the standard Resilience4j
  // API shape for subscribing to a CircuitBreaker's own events — there is no other way to reach
  // it, same rationale AntiAbuseRateLimitingFilter's own response.getWriter() suppression
  // documents.
  @SuppressWarnings("PMD.LawOfDemeter")
  public static void bind(
      final CircuitBreaker circuitBreaker, final SecurityMetricsRecorder metrics) {
    circuitBreaker
        .getEventPublisher()
        .onStateTransition(
            event ->
                metrics.increment(
                    STATE_TRANSITION_METRIC,
                    "name",
                    event.getCircuitBreakerName(),
                    "transition",
                    event.getStateTransition().name()))
        .onCallNotPermitted(
            event ->
                metrics.increment(
                    CALL_NOT_PERMITTED_METRIC, "name", event.getCircuitBreakerName()));
  }
}
