package com.clavaris.app.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisIndexedHttpSession;

/**
 * TD-ARCH-002 (closed): {@code HttpSession} backed by real Redis, not servlet-container heap — a
 * login session now survives an app restart and is visible to every running instance, closing the
 * last of the three "state only lived in JVM memory" gaps this codebase's own {@code
 * technical-debt-register.md} §5 sequencing named (signing keys/TD-SEC-002 and oauth2_authorization
 * /TD-SEC-003 closed first).
 *
 * <p>An explicit {@code @Configuration} class, not {@code application.yml} {@code spring.session.*}
 * properties — confirmed by inspecting the resolved {@code spring-boot-autoconfigure-4.1.0} jar
 * directly that Spring Boot 4.x ships zero session-related autoconfiguration classes at all (unlike
 * the still-autoconfigured {@code spring-boot-data-redis} module {@code
 * RedisFixedWindowRateLimiter} already depends on, confirmed present in that same jar) — a YAML
 * property here would be silently ignored, not a working config, the exact kind of "no silent
 * default/no-op config" mistake TD-SEC-013 already established this codebase avoids elsewhere.
 *
 * <p>{@code redisNamespace}: isolates every session key under its own prefix, distinct from {@code
 * RedisFixedWindowRateLimiter}'s own {@code ratelimit:*} keys sharing the same Redis instance
 * (ADR-0004). {@code indexed}, not the plain repository this annotation's sibling
 * ({@code @EnableRedisHttpSession}) would give: {@code PlatformDashboardSecurityConfig}'s {@code
 * SpringSessionBackedSessionRegistry} needs the find-by-principal-name index only the indexed
 * repository provides — see that class's own Javadoc for why.
 *
 * <p><b>Known test-suite-only log noise, not a production bug:</b> a full {@code mvn -pl app test}
 * run logs occasional {@code ERROR ... LettuceConnectionFactory has been STOPPED} lines from a
 * {@code spring-session-1} thread — this class's own {@code cleanupCron} background job
 * (fixed-rate, every minute, {@code RedisIndexedSessionRepository.cleanUpExpiredSessions}) firing
 * against a connection factory Spring's own {@code DefaultContextCache} paused ({@code
 * SmartLifecycle.stop()}) while evicting/switching between two different {@code @SpringBootTest}
 * configurations cached in the same JVM — confirmed by reading the logged stack trace's own {@code
 * DefaultContextCache.pauseOnContextSwitchIfNecessary} frame. The cron thread isn't itself part of
 * that pause, so it keeps firing against an already-stopped factory until that context is evicted
 * outright or the JVM exits. Harmless (caught by Spring's own {@code LoggingErrorHandler}, no test
 * ever fails from it) and specific to running many distinct context configurations back-to-back in
 * one test JVM — a real deployment has exactly one {@code ApplicationContext}, never paused this
 * way.
 */
@Configuration
@EnableRedisIndexedHttpSession(
    redisNamespace = "clavaris:sessions",
    maxInactiveIntervalInSeconds = 1800)
class DistributedSessionConfig {

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ DistributedSessionConfig() {
    // Intentionally empty — this class holds no state, only the class-level annotation above.
  }
}
