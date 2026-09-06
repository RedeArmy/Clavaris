package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.RateLimitPolicyRepository;
import com.clavaris.organization.domain.model.RateLimitPolicy;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * TD-FUT-012: proves the caching decorator itself, not the real Postgres round trip — that's
 * already covered by {@code JpaRateLimitPolicyRepositoryTest} for the delegate this class wraps.
 */
class CachingRateLimitPolicyRepositoryTest {

  private static final long TTL_SECONDS = 30;
  private static final long MAX_SIZE = 10_000;

  @Test
  void aSecondReadWithinTheTtlNeverReachesTheDelegate() {
    RateLimitPolicyRepository delegate = mock(RateLimitPolicyRepository.class);
    UUID organizationId = UUID.randomUUID();
    RateLimitPolicy policy = aPolicyFor(organizationId, 100);
    when(delegate.findByOrganizationId(organizationId)).thenReturn(Optional.of(policy));
    CachingRateLimitPolicyRepository cache =
        new CachingRateLimitPolicyRepository(delegate, TTL_SECONDS, MAX_SIZE);

    cache.findByOrganizationId(organizationId);
    Optional<RateLimitPolicy> second = cache.findByOrganizationId(organizationId);

    assertThat(second).contains(policy);
    verify(delegate, times(1)).findByOrganizationId(organizationId);
  }

  // BR-ORG-05: an Organization whose ceiling was never tuned is the common case — this must be
  // cached exactly like a real policy, not treated as "nothing to cache, always re-check."
  @Test
  void anAbsentPolicyIsAlsoCachedNotJustAPresentOne() {
    RateLimitPolicyRepository delegate = mock(RateLimitPolicyRepository.class);
    UUID organizationId = UUID.randomUUID();
    when(delegate.findByOrganizationId(organizationId)).thenReturn(Optional.empty());
    CachingRateLimitPolicyRepository cache =
        new CachingRateLimitPolicyRepository(delegate, TTL_SECONDS, MAX_SIZE);

    cache.findByOrganizationId(organizationId);
    Optional<RateLimitPolicy> second = cache.findByOrganizationId(organizationId);

    assertThat(second).isEmpty();
    verify(delegate, times(1)).findByOrganizationId(organizationId);
  }

  @Test
  void twoDifferentOrganizationsAreCachedIndependently() {
    RateLimitPolicyRepository delegate = mock(RateLimitPolicyRepository.class);
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    when(delegate.findByOrganizationId(first)).thenReturn(Optional.of(aPolicyFor(first, 100)));
    when(delegate.findByOrganizationId(second)).thenReturn(Optional.of(aPolicyFor(second, 200)));
    CachingRateLimitPolicyRepository cache =
        new CachingRateLimitPolicyRepository(delegate, TTL_SECONDS, MAX_SIZE);

    cache.findByOrganizationId(first);
    cache.findByOrganizationId(second);

    verify(delegate, times(1)).findByOrganizationId(first);
    verify(delegate, times(1)).findByOrganizationId(second);
  }

  @Test
  void anExpiredEntryFallsThroughToTheDelegateAgain() {
    RateLimitPolicyRepository delegate = mock(RateLimitPolicyRepository.class);
    UUID organizationId = UUID.randomUUID();
    when(delegate.findByOrganizationId(organizationId))
        .thenReturn(Optional.of(aPolicyFor(organizationId, 100)));
    // A zero-second TTL means every entry is already expired the instant it's written — the
    // simplest deterministic way to prove the fall-through path without sleeping in a test.
    CachingRateLimitPolicyRepository cache =
        new CachingRateLimitPolicyRepository(delegate, 0, MAX_SIZE);

    cache.findByOrganizationId(organizationId);
    cache.findByOrganizationId(organizationId);

    verify(delegate, times(2)).findByOrganizationId(organizationId);
  }

  // TD-FUT-012's own accepted design: a write updates the cache immediately (write-through), so an
  // operator's change to a tenant's own capacity ceiling is never masked by a stale cached read for
  // up to `ttl` afterward.
  @Test
  void savePopulatesTheCacheSoTheNextReadNeverReachesTheDelegate() {
    RateLimitPolicyRepository delegate = mock(RateLimitPolicyRepository.class);
    UUID organizationId = UUID.randomUUID();
    RateLimitPolicy policy = aPolicyFor(organizationId, 250);
    CachingRateLimitPolicyRepository cache =
        new CachingRateLimitPolicyRepository(delegate, TTL_SECONDS, MAX_SIZE);

    cache.save(policy);
    Optional<RateLimitPolicy> read = cache.findByOrganizationId(organizationId);

    assertThat(read).contains(policy);
    verify(delegate, never()).findByOrganizationId(organizationId);
  }

  // TD-PERF-006: the actual fix this row asked for — a real, bounded cache, not an unbounded
  // ConcurrentHashMap. A tiny maxSize (2) and more distinct Organizations than that fits proves
  // Caffeine's own eviction genuinely runs, not just that the API compiles.
  @Test
  void cacheSizeStaysBoundedByMaxSizeNotUnbounded() {
    RateLimitPolicyRepository delegate = mock(RateLimitPolicyRepository.class);
    long smallMaxSize = 2;
    CachingRateLimitPolicyRepository cache =
        new CachingRateLimitPolicyRepository(delegate, TTL_SECONDS, smallMaxSize);
    for (int i = 0; i < 10; i++) {
      UUID organizationId = UUID.randomUUID();
      cache.save(aPolicyFor(organizationId, 100));
    }

    assertThat(cache.estimatedSizeAfterCleanup()).isLessThanOrEqualTo(smallMaxSize);
  }

  @Test
  void saveAlwaysDelegatesTheRealWrite() {
    RateLimitPolicyRepository delegate = mock(RateLimitPolicyRepository.class);
    RateLimitPolicy policy = aPolicyFor(UUID.randomUUID(), 100);
    CachingRateLimitPolicyRepository cache =
        new CachingRateLimitPolicyRepository(delegate, TTL_SECONDS, MAX_SIZE);

    cache.save(policy);

    verify(delegate).save(policy);
  }

  private static RateLimitPolicy aPolicyFor(
      final UUID organizationId, final int requestsPerMinute) {
    Instant now = Instant.now();
    return RateLimitPolicy.reconstitute(
        UUID.randomUUID(), organizationId, requestsPerMinute, now, now);
  }
}
