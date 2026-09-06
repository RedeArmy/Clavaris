package com.clavaris.app.infrastructure.config;

import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.RateLimitPolicyRepository;
import com.clavaris.organization.domain.model.RateLimitPolicy;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

/**
 * TD-FUT-012 (closed 2026-09-02): {@link OrganizationCapacityRateLimitingFilter} previously read
 * {@link RateLimitPolicyRepository} straight from Postgres on every single {@code /o/*} request — a
 * deliberate, documented "correct and simple first" call at the time (same {@code
 * common}/TD-ARCH-001 precedent {@link OrganizationSocialLoginPolicyProviderBridge}'s own Javadoc
 * still cites), now closed with the short-TTL in-memory cache that row's own description already
 * named as the natural fix.
 *
 * <p>{@code @Primary}: this decorator, not the real {@code JpaRateLimitPolicyRepository}, is what
 * every other caller of the {@link RateLimitPolicyRepository} port now receives by default —
 * including {@code SetRateLimitPolicyForOrganizationService}, whose own {@link #save} call below is
 * exactly how the cache stays correct on a write, not just on TTL expiry (see {@link #save}'s own
 * Javadoc). The real JPA adapter is looked up by its own default Spring bean name,
 * {@code @Qualifier("jpaRateLimitPolicyRepository")} — {@code app} can only reach it through the
 * {@link RateLimitPolicyRepository} port's own type (the concrete class is package-private inside
 * organization-module's persistence package, by the same dependency-direction rule every other
 * bridge in this package respects), so a qualifier by bean name is the only way to disambiguate two
 * beans of the same interface type without one injecting itself.
 *
 * <p>TD-PERF-006: previously a hand-rolled unbounded {@code ConcurrentHashMap} — a deleted
 * Organization's own entry was never purged, only ever overwritten on its own next read (which
 * never comes), so memory grew monotonically with total Organization churn over the process's
 * lifetime, not current Organization count. Caffeine's own {@code maximumSize} (Window TinyLFU
 * eviction) replaces that with a real, bounded cache that evicts the least-recently-used entries
 * once full — a deleted Organization's entry simply stops being accessed and ages out on its own,
 * with no explicit {@code evict(key)} call needed on every current and future Organization-deletion
 * code path (a real Organization hard-delete flow already exists, {@code
 * DeleteOrganizationService}, and every future one would otherwise need to remember this cache
 * too).
 *
 * <p>Deliberately not {@code final}, unlike most classes in this codebase — {@code @Repository} (as
 * opposed to {@code @Component}) makes this bean eligible for {@code
 * PersistenceExceptionTranslationPostProcessor}, which wraps it in a CGLIB proxy; CGLIB proxies a
 * bean by subclassing it, which a {@code final} class structurally cannot support (confirmed live —
 * a {@code final} version of this class fails Spring context startup with {@code
 * AopConfigException: Cannot subclass final class}, not a theoretical concern). Every other
 * {@code @Repository} in this codebase is already non-final for the same reason.
 */
@Repository
@Primary
class CachingRateLimitPolicyRepository implements RateLimitPolicyRepository {

  private final RateLimitPolicyRepository delegate;
  private final Cache<UUID, Optional<RateLimitPolicy>> cache;

  /* package */ CachingRateLimitPolicyRepository(
      @Qualifier("jpaRateLimitPolicyRepository") final RateLimitPolicyRepository delegate,
      @Value("${clavaris.rate-limit.capacity.policy-cache-ttl-seconds:30}") final long ttlSeconds,
      // TD-PERF-006: real Organization count is expected to stay small for the foreseeable future
      // (same order-of-magnitude judgment TD-ARCH-001/common's own deliberately-empty caching
      // layer already makes) — 10,000 is generous headroom over that, not a number this project
      // expects to actually approach, while still being a real, finite bound instead of none.
      @Value("${clavaris.rate-limit.capacity.policy-cache-max-size:10000}") final long maxSize) {
    this.delegate = delegate;
    this.cache =
        Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
            .maximumSize(maxSize)
            .build();
  }

  // TD-FUT-012's own row explicitly accepted a simple "read-through, TTL-bounded staleness" shape
  // over a fully-consistent invalidation scheme — SetRateLimitPolicyController writes are rare,
  // operator-only actions, not a hot path this needs to optimize consistency for. Cache#get with a
  // mapping function (Caffeine's own atomic compute-if-absent) also closes a small pre-existing
  // race the hand-rolled get-then-put version had: two concurrent misses for the same
  // organizationId could previously both fall through to delegate.findByOrganizationId — harmless
  // here (idempotent read), but no longer even possible.
  @Override
  public Optional<RateLimitPolicy> findByOrganizationId(final UUID organizationId) {
    return cache.get(organizationId, delegate::findByOrganizationId);
  }

  // Write-through, not merely invalidate-and-let-the-next-read-repopulate: an operator changing a
  // tenant's own capacity ceiling (SetRateLimitPolicyController) expects that change to apply
  // immediately, not after up to `ttl` of the old value still being enforced — the whole point of
  // the TTL is bounding *cold-start* staleness (a value this instance has never read before), not
  // tolerating stale reads right after a write this same process just made.
  @Override
  public void save(final RateLimitPolicy policy) {
    delegate.save(policy);
    cache.put(policy.organizationId(), Optional.of(policy));
  }

  // Test-only observability, not used by any production code path — proves TD-PERF-006's own
  // actual point (a real, bounded cache) directly, rather than only indirectly through delegate
  // call counts. cleanUp() forces Caffeine's own otherwise-opportunistic eviction maintenance to
  // run synchronously, so a size assertion taken right after this call is deterministic.
  /* package */ long estimatedSizeAfterCleanup() {
    cache.cleanUp();
    return cache.estimatedSize();
  }
}
