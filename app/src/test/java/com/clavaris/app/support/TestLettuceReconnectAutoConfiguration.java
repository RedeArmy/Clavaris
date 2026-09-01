package com.clavaris.app.support;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;

/**
 * TD-TEST-006 (SDE-III review, 2026-08-31): picked up automatically by every
 * {@code @SpringBootTest} context in this module via {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} — Spring Boot's
 * own classpath-merging auto-configuration import mechanism, safe alongside this module's real
 * {@code src/main/resources/application.yml} (unlike a competing {@code
 * src/test/resources/application.yml}, which would fully shadow it instead of merging — see
 * TD-TEST-005's own closure note).
 *
 * <p>Disables Lettuce's own indefinite auto-reconnect for every Redis connection any test in this
 * module opens. Production keeps auto-reconnect on ({@code DistributedSessionConfig}'s real config,
 * untouched here) — a genuine resilience property against a transient Redis blip, the same
 * reasoning TD-SEC-022's Redis-fail-open work already established for this codebase. In this
 * module's own test suite specifically, auto-reconnect is actively harmful, not merely unneeded:
 * Testcontainers tears down a Redis container the instant its owning test class finishes, but
 * Spring's own test-context cache can keep a Spring context — and the {@code
 * LettuceConnectionFactory}/{@code ConnectionWatchdog} it created — alive well past that point,
 * right up to JVM shutdown. Confirmed live: a {@code ConnectionWatchdog} retrying forever against
 * an already-gone container blocked the whole Surefire fork from ever exiting, past its own 30s
 * {@code forkedProcessExitTimeoutInSeconds} ("Surefire is going to kill self fork JVM..."), even
 * after TD-TEST-005's own pgjdbc-specific fix and a raised {@code
 * spring.test.context.cache.maxSize} both landed. With auto-reconnect off, a dead connection simply
 * stays dead — no retry loop, no lingering Netty threads, nothing left to block a clean JVM exit.
 */
@AutoConfiguration
class TestLettuceReconnectAutoConfiguration {

  @Bean
  LettuceClientOptionsBuilderCustomizer disableAutoReconnectForTests() {
    return builder -> builder.autoReconnect(false);
  }
}
