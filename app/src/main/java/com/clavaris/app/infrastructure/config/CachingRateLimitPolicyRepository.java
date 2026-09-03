package com.clavaris.app.infrastructure.config;

import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.RateLimitPolicyRepository;
import com.clavaris.organization.domain.model.RateLimitPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p>Cache key is the raw {@code organizationId} — unbounded in principle (nothing evicts a deleted
 * Organization's own now-orphaned entry), but bounded in practice by real Organization count, which
 * is expected to stay small for the foreseeable future (same order-of-magnitude judgment call this
 * codebase already makes for TD-ARCH-001/{@code common}'s own deliberately-empty caching layer) — a
 * genuinely unbounded key space (per-request, per-user) would need a real eviction policy; a
 * per-tenant one does not, yet.
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
  private final Duration ttl;
  // Declared as the Map interface (PMD.LooseCoupling), not the concrete ConcurrentHashMap type —
  // nothing here uses any ConcurrentHashMap-specific method beyond plain get/put, both on Map
  // itself; the concrete type is still what's actually constructed, for real thread-safe
  // read/write access from concurrent requests.
  private final Map<UUID, CacheEntry> cache = new ConcurrentHashMap<>();

  /* package */ CachingRateLimitPolicyRepository(
      @Qualifier("jpaRateLimitPolicyRepository") final RateLimitPolicyRepository delegate,
      @Value("${clavaris.rate-limit.capacity.policy-cache-ttl-seconds:30}") final long ttlSeconds) {
    this.delegate = delegate;
    this.ttl = Duration.ofSeconds(ttlSeconds);
  }

  // A live cache entry (including a genuinely absent policy, Optional.empty() — the common case
  // for any Organization whose ceiling was never tuned, BR-ORG-05) is returned as-is; anything
  // missing or expired falls through to a real read, which then populates/refreshes the entry.
  // TD-FUT-012's own row explicitly accepted this simple "read-through, TTL-bounded staleness"
  // shape over a fully-consistent invalidation scheme — SetRateLimitPolicyController writes are
  // rare, operator-only actions, not a hot path this needs to optimize consistency for.
  // PMD.OnlyOneReturn: the fresh-cache-hit early return and the fall-through-and-populate path are
  // two independent, equally valid exits — same rationale as every other early-return chain in
  // this codebase.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Override
  public Optional<RateLimitPolicy> findByOrganizationId(final UUID organizationId) {
    final Instant now = Instant.now();
    final CacheEntry cached = cache.get(organizationId);
    if (cached != null && cached.expiresAt().isAfter(now)) {
      return cached.value();
    }
    final Optional<RateLimitPolicy> fresh = delegate.findByOrganizationId(organizationId);
    cache.put(organizationId, new CacheEntry(fresh, now.plus(ttl)));
    return fresh;
  }

  // Write-through, not merely invalidate-and-let-the-next-read-repopulate: an operator changing a
  // tenant's own capacity ceiling (SetRateLimitPolicyController) expects that change to apply
  // immediately, not after up to `ttl` of the old value still being enforced — the whole point of
  // the TTL is bounding *cold-start* staleness (a value this instance has never read before), not
  // tolerating stale reads right after a write this same process just made.
  @Override
  public void save(final RateLimitPolicy policy) {
    delegate.save(policy);
    cache.put(
        policy.organizationId(), new CacheEntry(Optional.of(policy), Instant.now().plus(ttl)));
  }

  private record CacheEntry(Optional<RateLimitPolicy> value, Instant expiresAt) {}
}
