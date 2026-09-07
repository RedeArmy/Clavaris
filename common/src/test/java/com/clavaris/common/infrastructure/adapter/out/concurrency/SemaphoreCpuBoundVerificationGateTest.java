package com.clavaris.common.infrastructure.adapter.out.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * TD-FUT-017: proves the two outcomes {@link CpuBoundVerificationGate}'s own Javadoc promises never
 * get conflated — a permit-available call runs the real work and returns its real result; a
 * saturated gate refuses to run the work at all, deterministically (a held permit + a short wait
 * bound), not via a timing-sensitive sleep-and-hope.
 */
class SemaphoreCpuBoundVerificationGateTest {

  @Test
  void admitsAndReturnsTheRealResultWhenAPermitIsAvailable() {
    RecordingMetrics metrics = new RecordingMetrics();
    SemaphoreCpuBoundVerificationGate gate = new SemaphoreCpuBoundVerificationGate(1, 250, metrics);

    Optional<Boolean> matched = gate.runBounded(() -> true);
    Optional<Boolean> notMatched = gate.runBounded(() -> false);

    assertThat(matched).contains(true);
    assertThat(notMatched).contains(false);
    assertThat(metrics.outcomesTagged("admitted")).hasSize(2);
  }

  @Test
  void rejectsWithoutRunningTheWorkWhenTheSolePermitIsHeldPastTheWaitBound() throws Exception {
    RecordingMetrics metrics = new RecordingMetrics();
    // maxConcurrentVerifications=1: the second call below has nothing left to acquire while the
    // first is still in flight.
    SemaphoreCpuBoundVerificationGate gate = new SemaphoreCpuBoundVerificationGate(1, 50, metrics);
    CountDownLatch holderHasThePermit = new CountDownLatch(1);
    CountDownLatch releaseTheHolder = new CountDownLatch(1);
    AtomicReference<Optional<Boolean>> holderResult = new AtomicReference<>();

    Thread holder =
        new Thread(
            () ->
                holderResult.set(
                    gate.runBounded(
                        () -> {
                          holderHasThePermit.countDown();
                          await(releaseTheHolder);
                          return true;
                        })));
    holder.start();
    holderHasThePermit.await(5, TimeUnit.SECONDS);

    // The sole permit is provably held right now (the thread above is blocked inside its own
    // verification body) — this call has 50ms to acquire one and can't, so it must be rejected
    // without ever invoking the supplier below.
    AtomicReference<Boolean> rejectedSupplierRan = new AtomicReference<>(false);
    Optional<Boolean> rejected =
        gate.runBounded(
            () -> {
              rejectedSupplierRan.set(true);
              return true;
            });

    assertThat(rejected).isEmpty();
    assertThat(rejectedSupplierRan).hasValue(false);
    assertThat(metrics.outcomesTagged("rejected")).hasSize(1);

    releaseTheHolder.countDown();
    holder.join(5_000);
    assertThat(holderResult.get()).contains(true);
    assertThat(metrics.outcomesTagged("admitted")).hasSize(1);
  }

  private static void await(final CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (final InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }

  /** Minimal fake, not a mock — records every increment call verbatim for direct assertion. */
  private static final class RecordingMetrics implements SecurityMetricsRecorder {
    private final List<String> outcomes = new ArrayList<>();

    @Override
    public void increment(final String metricName, final String... tagKeyValuePairs) {
      for (int i = 0; i < tagKeyValuePairs.length - 1; i += 2) {
        if ("outcome".equals(tagKeyValuePairs[i])) {
          outcomes.add(tagKeyValuePairs[i + 1]);
        }
      }
    }

    private List<String> outcomesTagged(final String outcome) {
      return outcomes.stream().filter(outcome::equals).toList();
    }
  }
}
