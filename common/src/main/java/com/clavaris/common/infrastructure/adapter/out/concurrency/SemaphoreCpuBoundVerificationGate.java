package com.clavaris.common.infrastructure.adapter.out.concurrency;

import com.clavaris.common.application.port.CpuBoundVerificationGate;
import com.clavaris.common.application.port.SecurityMetricsRecorder;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * TD-FUT-017: the one real implementation of {@link CpuBoundVerificationGate} — a plain JDK {@link
 * Semaphore}, deliberately not a new library dependency (Resilience4j's own {@code Bulkhead} does
 * the same thing, but pulling in a whole new dependency for one semaphore is disproportionate).
 *
 * <p>Sized to {@code Runtime.availableProcessors()} by default — Argon2id verification is CPU-bound
 * and doesn't get faster with more concurrent threads than there are cores to run them on
 * (`load-testing/README.md` §4's own measured finding); admitting more than that many at once only
 * adds queueing delay, not throughput. Overridable via {@code
 * clavaris.security.argon2.max-concurrent-verifications} for a production host with a known,
 * different core count once one exists (TD-FUT-013).
 *
 * <p>A <b>fair</b> semaphore ({@code new Semaphore(n, true)}), not the default unfair one — under
 * sustained overload an unfair semaphore can starve a request that arrived first in favour of one
 * that arrived later and got lucky with scheduling; fairness costs a small amount of throughput in
 * exchange for a bounded, predictable per-caller wait, the right trade for an admission-control
 * gate whose whole point is a predictable worst case, not maximum throughput.
 */
@Component
class SemaphoreCpuBoundVerificationGate implements CpuBoundVerificationGate {

  private static final Logger LOG =
      LoggerFactory.getLogger(SemaphoreCpuBoundVerificationGate.class);
  private static final String METRIC_NAME = "clavaris.auth.argon2_bulkhead";
  private static final String OUTCOME_TAG_KEY = "outcome";

  private final Semaphore permits;
  private final Duration maxWait;
  private final SecurityMetricsRecorder metrics;

  // PMD.LongVariable: maxConcurrentVerifications names exactly what it holds — shortening it would
  // make the constructor's own @Value default expression (spelling out the same concept) harder to
  // read, not easier.
  @SuppressWarnings("PMD.LongVariable")
  /* package */ SemaphoreCpuBoundVerificationGate(
      @Value(
              "${clavaris.security.argon2.max-concurrent-verifications:"
                  + "#{T(java.lang.Runtime).getRuntime().availableProcessors()}}")
          final int maxConcurrentVerifications,
      @Value("${clavaris.security.argon2.max-wait-millis:250}") final long maxWaitMillis,
      final SecurityMetricsRecorder metrics) {
    this.permits = new Semaphore(maxConcurrentVerifications, true);
    this.maxWait = Duration.ofMillis(maxWaitMillis);
    this.metrics = metrics;
  }

  // Three genuinely distinct exits (interrupted, saturated/rejected, admitted-and-ran) — same "one
  // exit per distinct outcome" rationale PrimaryFactorLoginCompletion's own identical suppression
  // already establishes in identity-module.
  @Override
  @SuppressWarnings("PMD.OnlyOneReturn")
  public Optional<Boolean> runBounded(final BooleanSupplier verification) {
    final boolean acquired;
    try {
      acquired = permits.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS);
    } catch (final InterruptedException _) {
      // Restore the interrupt flag rather than swallow it (standard practice) — but still report
      // this the same way as an ordinary rejection to the caller, since the verification
      // genuinely never ran either way.
      Thread.currentThread().interrupt();
      metrics.increment(METRIC_NAME, OUTCOME_TAG_KEY, "interrupted");
      return Optional.empty();
    }
    if (!acquired) {
      // BR-DATA-01: no credential/secret value anywhere near this line, only the fact that the
      // gate itself is saturated — a real operational signal (TokenIssuanceLatencyHigh-style
      // alerting, TD-FUT-011, can watch this metric the same way it already watches p95 latency).
      LOG.warn("event=argon2_bulkhead_rejected");
      metrics.increment(METRIC_NAME, OUTCOME_TAG_KEY, "rejected");
      return Optional.empty();
    }
    try {
      final boolean result = verification.getAsBoolean();
      metrics.increment(METRIC_NAME, OUTCOME_TAG_KEY, "admitted");
      return Optional.of(result);
    } finally {
      permits.release();
    }
  }
}
