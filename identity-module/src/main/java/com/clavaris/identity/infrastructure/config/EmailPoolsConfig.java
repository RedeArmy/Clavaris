package com.clavaris.identity.infrastructure.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The two bounded pools the emailed requests do their account-dependent work on, so the answer to a
 * request never reveals whether the address has an Account. Small and bounded on purpose, the same
 * shape and reasoning as {@code newDeviceNotificationExecutor} (TD-PERF-011/040): a mail-provider
 * outage must not let queued tasks pile up in memory, and past the capacity a task is rejected,
 * which the services log and drop. A rejection is also counted, and each pool's queue is on {@code
 * /actuator/prometheus} ({@code ExecutorMetricsConfig}), so an outage that fills one pages someone
 * before people wait for mail that was never sent (TD-PERF-055).
 */
@Configuration
class EmailPoolsConfig {

  // Written out for the same reason as every config class here: only Spring's component scan
  // instantiates it.
  /* package */ EmailPoolsConfig() {
    // Intentionally empty — this class holds no state, only @Bean methods.
  }

  // The pool the sign-in code and link requests do their account-dependent work on, so the response
  // to a request never reveals whether the address has an account (see the two Request* services).
  // Small and bounded on purpose, the same shape and reasoning as newDeviceNotificationExecutor
  // (TD-PERF-011/040): a mail-provider outage must not let queued tasks pile up in memory, and past
  // the capacity a task is rejected, which the services log and drop. destroyMethod="shutdown" so a
  // repeatedly started @SpringBootTest context does not leak threads.
  //
  // A rejection used to leave one WARN line and nothing else, while the person still got the usual
  // "check your email" page. It is now also counted (clavaris.email.request.rejected), and the
  // queue
  // itself is on /actuator/prometheus (ExecutorMetricsConfig), so a provider outage that fills it
  // pages someone before people wait for mail that was never sent.
  @Bean(destroyMethod = "shutdown")
  /* package */ ExecutorService emailRequestExecutor(
      @Value("${clavaris.email-request.concurrency:4}") final int concurrency,
      @Value("${clavaris.email-request.queue-capacity:1000}") final int queueCapacity,
      final ObjectProvider<MeterRegistry> meterRegistry) {
    return boundedEmailPool(
        "email-request",
        concurrency,
        queueCapacity,
        "clavaris.email.request.rejected",
        meterRegistry);
  }

  // TD-PERF-054: the mail that gets a person back into their account, on a pool of its own. The
  // password-reset request, the verification resend and the lockout notice are the messages with
  // recovery value; they used to share one queue with the sign-in code and link requests, which are
  // cheap to send in bulk (limited per address and per IP, but not globally), so a burst of those
  // could sit ahead of, or push out, a reset. Smaller, because far fewer of them are sent; rejected
  // work is counted under its own metric (clavaris.email.recovery.rejected).
  @Bean(destroyMethod = "shutdown")
  /* package */ ExecutorService recoveryEmailExecutor(
      @Value("${clavaris.email-recovery.concurrency:2}") final int concurrency,
      @Value("${clavaris.email-recovery.queue-capacity:200}") final int queueCapacity,
      final ObjectProvider<MeterRegistry> meterRegistry) {
    return boundedEmailPool(
        "email-recovery",
        concurrency,
        queueCapacity,
        "clavaris.email.recovery.rejected",
        meterRegistry);
  }

  private static ExecutorService boundedEmailPool(
      final String threadPrefix,
      final int concurrency,
      final int queueCapacity,
      final String rejectedMetric,
      final ObjectProvider<MeterRegistry> meterRegistry) {
    final AtomicInteger threadCount = new AtomicInteger();
    final ThreadFactory namedThreads =
        runnable -> {
          final Thread thread =
              new Thread(runnable, threadPrefix + "-" + threadCount.incrementAndGet());
          // Never blocks JVM shutdown on an in-flight send.
          thread.setDaemon(true);
          return thread;
        };
    return new ThreadPoolExecutor(
        concurrency,
        concurrency,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(queueCapacity),
        namedThreads,
        countingAbortPolicy(meterRegistry, rejectedMetric));
  }

  // AbortPolicy's behaviour (the caller sees RejectedExecutionException and logs it), plus a
  // counter. Package-private so a test can saturate a small pool and read it.
  /* package */ static RejectedExecutionHandler countingAbortPolicy(
      final ObjectProvider<MeterRegistry> meterRegistry, final String metricName) {
    final ThreadPoolExecutor.AbortPolicy abort = new ThreadPoolExecutor.AbortPolicy();
    return (task, pool) -> {
      meterRegistry.ifAvailable(registry -> registry.counter(metricName).increment());
      abort.rejectedExecution(task, pool);
    };
  }
}
