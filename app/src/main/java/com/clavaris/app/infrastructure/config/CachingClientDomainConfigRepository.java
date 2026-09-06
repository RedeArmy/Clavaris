package com.clavaris.app.infrastructure.config;

import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.ClientDomainConfigRepository;
import com.clavaris.clientregistry.domain.model.ClientDomainConfig;
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
 * ADR-0009 §4: {@link CustomDomainRequestRewriteFilter} calls {@link #findByHostname} on every
 * single request that isn't already {@code /o/**}/{@code /oauth2/**} — a genuine hot path, same
 * "read straight from Postgres on every request" gap {@code CachingRateLimitPolicyRepository}'s own
 * Javadoc already documents and fixes for {@code OrganizationCapacityRateLimitingFilter}; this
 * class follows that exact same short-TTL, read-through, write-through shape.
 *
 * <p>{@code @Primary}: this decorator, not the real {@code JpaClientDomainConfigRepository}, is
 * what every other caller of the port receives by default. The real JPA adapter is looked up by its
 * own default Spring bean name, {@code @Qualifier("jpaClientDomainConfigRepository")} — same
 * disambiguation reasoning {@code CachingRateLimitPolicyRepository}'s own Javadoc documents.
 *
 * <p>Two independent cache maps (by hostname, by {@code oauthClientId}) rather than one keyed by a
 * synthetic compound key — {@link CustomDomainRequestRewriteFilter} only ever looks up by hostname,
 * while the admin GET/verify endpoints only ever look up by {@code oauthClientId}; a write (a
 * domain request/re-request/verification outcome) updates both, since either key could already hold
 * a now-stale entry for the same row.
 */
@Repository
@Primary
class CachingClientDomainConfigRepository implements ClientDomainConfigRepository {

  private final ClientDomainConfigRepository delegate;
  private final Duration ttl;
  // Declared as the Map interface (PMD.LooseCoupling) — same convention
  // CachingRateLimitPolicyRepository's own identical fields already establish.
  private final Map<String, CacheEntry> byHostname = new ConcurrentHashMap<>();
  private final Map<UUID, CacheEntry> byOauthClientId = new ConcurrentHashMap<>();

  /* package */ CachingClientDomainConfigRepository(
      @Qualifier("jpaClientDomainConfigRepository") final ClientDomainConfigRepository delegate,
      @Value("${clavaris.client-domain.config-cache-ttl-seconds:30}") final long ttlSeconds) {
    this.delegate = delegate;
    this.ttl = Duration.ofSeconds(ttlSeconds);
  }

  @Override
  public Optional<ClientDomainConfig> findByOAuthClientId(final UUID oauthClientId) {
    return readThrough(
        byOauthClientId, oauthClientId, () -> delegate.findByOAuthClientId(oauthClientId));
  }

  @Override
  public Optional<ClientDomainConfig> findByHostname(final String hostname) {
    return readThrough(byHostname, hostname, () -> delegate.findByHostname(hostname));
  }

  // TD-FUT-012-style read-through, TTL-bounded staleness — same rationale
  // CachingRateLimitPolicyRepository#findByOrganizationId's own identical comment documents.
  // PMD.OnlyOneReturn: the fresh-cache-hit early return and the fall-through-and-populate path are
  // two independent, equally valid exits — same convention as that sibling method.
  @SuppressWarnings("PMD.OnlyOneReturn")
  private <K> Optional<ClientDomainConfig> readThrough(
      final Map<K, CacheEntry> cache,
      final K key,
      final java.util.function.Supplier<Optional<ClientDomainConfig>> loader) {
    final Instant now = Instant.now();
    final CacheEntry cached = cache.get(key);
    if (cached != null && cached.expiresAt().isAfter(now)) {
      return cached.value();
    }
    final Optional<ClientDomainConfig> fresh = loader.get();
    cache.put(key, new CacheEntry(fresh, now.plus(ttl)));
    return fresh;
  }

  // Write-through on both keys, not merely invalidate-and-let-the-next-read-repopulate — same
  // "an operator's own change must apply immediately" rationale
  // CachingRateLimitPolicyRepository#save's own identical comment documents. A hostname change
  // (reRequest with a new hostname) leaves the OLD hostname's cache entry to expire naturally on
  // its own TTL rather than being explicitly evicted — same bounded-staleness trade-off, and the
  // old hostname is no longer claimed by this row's own oauthClientId once ownership moves, so a
  // stale hit there would only ever resolve to a row that no longer points at it once re-read.
  @Override
  public void save(final ClientDomainConfig config) {
    delegate.save(config);
    final Instant expiresAt = Instant.now().plus(ttl);
    final Optional<ClientDomainConfig> fresh = Optional.of(config);
    byOauthClientId.put(config.oauthClientId(), new CacheEntry(fresh, expiresAt));
    config
        .hostname()
        .ifPresent(hostname -> byHostname.put(hostname, new CacheEntry(fresh, expiresAt)));
  }

  private record CacheEntry(Optional<ClientDomainConfig> value, Instant expiresAt) {}
}
