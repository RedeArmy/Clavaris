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
 * <p><b>Test-suite-only log noise, previously left as-is, now closed:</b> a full {@code mvn -pl app
 * test} run used to log occasional {@code ERROR ... LettuceConnectionFactory has been STOPPED}
 * lines from a {@code spring-session-1} thread — this class's own {@code cleanupCron} background
 * job (fixed-rate, every minute, {@code RedisIndexedSessionRepository.cleanUpExpiredSessions})
 * firing against a connection factory Spring's own {@code DefaultContextCache} paused ({@code
 * SmartLifecycle.stop()}) while evicting/switching between two different {@code @SpringBootTest}
 * configurations cached in the same JVM. Harmless (caught by Spring's own {@code
 * LoggingErrorHandler}, no test ever failed from it) but revisited on explicit request rather than
 * left as accepted noise — {@code RedisBackedIntegrationTest} now imports {@code
 * SessionCleanupCronDisabledForTestsConfig}, which disables this job outright for every test that
 * extends it (Spring Session's own documented {@code "-"} sentinel, applied via a {@code
 * BeanPostProcessor} before this class's own {@code RedisIndexedSessionRepository} ever schedules
 * its cron — see that test config's own Javadoc for why a property override couldn't do it). Real
 * deployments are unaffected: this class's own annotation attribute, and therefore production's
 * real minute-by-minute cleanup, is untouched.
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
