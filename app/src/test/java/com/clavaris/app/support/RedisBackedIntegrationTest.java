package com.clavaris.app.support;

import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * ADR-0010 §6: real bug, confirmed live via CI — every {@code @SpringBootTest} that exercises
 * {@code OrganizationAuthorizationServerConfig}/{@code PlatformAuthorizationServerConfig}/{@code
 * PlatformDashboardSecurityConfig}'s filter chains now runs through {@code
 * AntiAbuseRateLimitingFilter}/{@code OrganizationCapacityRateLimitingFilter}, both of which call a
 * real Redis on every matching request. Locally this was masked by {@code docker-compose.yml}'s own
 * Redis container already listening on {@code localhost:6379}; in CI (no such container) any test
 * class that didn't explicitly provision Redis got a real {@code RedisConnectionFailureException} →
 * 500 the moment it hit any rate-limited endpoint — surfacing as unrelated-looking NPEs, missing
 * CSRF tokens, and wrong status codes across a dozen otherwise-passing tests, not as an obvious
 * "Redis is down" failure.
 *
 * <p>One shared Testcontainers Redis instance per test class, wired the same way {@code
 * RateLimitingIntegrationTest} already did it (no dedicated Testcontainers Redis module dependency
 * in this project, hence {@code @DynamicPropertySource} rather than {@code @ServiceConnection}) —
 * extend this class instead of duplicating the container/property-source boilerplate.
 *
 * <p>Also the one shared place that neutralizes both of this module's known short-interval
 * background jobs for the duration of any test — see {@link
 * #WEBHOOK_SCHEDULER_NEVER_FIRES_IN_A_TEST_MS} below and {@link
 * SessionCleanupCronDisabledForTestsConfig}'s own Javadoc — so a subclass never has to reason about
 * either one racing its own container teardown.
 */
@Testcontainers
@Import(SessionCleanupCronDisabledForTestsConfig.class)
public abstract class RedisBackedIntegrationTest {

  // A real Spring Test context lives only as long as its own class's tests (or until the context
  // cache evicts it) — but WebhookDispatchScheduler's own production defaults (5-10s) are tuned for
  // real responsiveness, short enough that a real tick can fire mid-suite and then race a
  // Postgres/Redis container tearing down under it. Confirmed live: exactly this race produced
  // sporadic ERROR-level `event=webhook_dispatch_tick_failed`/`event=webhook_delivery_tick_failed`
  // (CannotCreateTransactionException, a dying HikariPool) during otherwise-green full-reactor runs
  // — flaky, environment-timing-dependent log noise, not a real product defect (real coverage of
  // both use cases already exists via WebhookDispatchSchedulerTest's own mocks and each service's
  // dedicated Testcontainers-backed test). A one-year initial delay never elapses within any test
  // run's finite lifetime, so neither tick ever actually fires here.
  private static final long WEBHOOK_SCHEDULER_NEVER_FIRES_IN_A_TEST_MS =
      TimeUnit.DAYS.toMillis(365);

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

  @DynamicPropertySource
  static void redisProperties(final DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add(
        "clavaris.webhook.dispatch-initial-delay-ms",
        () -> WEBHOOK_SCHEDULER_NEVER_FIRES_IN_A_TEST_MS);
    registry.add(
        "clavaris.webhook.delivery-initial-delay-ms",
        () -> WEBHOOK_SCHEDULER_NEVER_FIRES_IN_A_TEST_MS);
  }

  // TD-ARCH-002: exposes this same shared container's real host/port to subclasses outside this
  // package (REDIS itself stays package-private — no reason to widen the field's own visibility
  // just so one test can point a second, independently-built Spring context at the identical
  // Testcontainer instance to simulate a genuinely separate app process).
  protected static String redisHost() {
    return REDIS.getHost();
  }

  protected static int redisPort() {
    return REDIS.getMappedPort(6379);
  }
}
