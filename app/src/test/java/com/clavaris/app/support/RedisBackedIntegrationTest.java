package com.clavaris.app.support;

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
 */
@Testcontainers
public abstract class RedisBackedIntegrationTest {

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

  @DynamicPropertySource
  static void redisProperties(final DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }
}
