package com.clavaris.app.infrastructure.config;

import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.ClientDomainConfigRepository;
import com.clavaris.clientregistry.domain.model.ClientDomainConfig;
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
 * <p>Two independent Caffeine caches (by hostname, by {@code oauthClientId}) rather than one keyed
 * by a synthetic compound key — {@link CustomDomainRequestRewriteFilter} only ever looks up by
 * hostname, while the admin GET/verify endpoints only ever look up by {@code oauthClientId}; a
 * write (a domain request/re-request/verification outcome) updates both, since either key could
 * already hold a now-stale entry for the same row.
 *
 * <p>TD-PERF-006: previously two hand-rolled unbounded {@code ConcurrentHashMap}s — a deleted or
 * re-hostnamed row's own stale key was never purged, only ever overwritten on its own next read
 * (which, for a genuinely abandoned hostname, never comes). Caffeine's own {@code maximumSize}
 * (Window TinyLFU eviction) bounds both caches for real, evicting least-recently-used entries once
 * full instead of growing with total historical churn — see {@code
 * CachingRateLimitPolicyRepository}'s own identical Javadoc for the full reasoning, shared verbatim
 * here.
 */
@Repository
@Primary
class CachingClientDomainConfigRepository implements ClientDomainConfigRepository {

  private final ClientDomainConfigRepository delegate;
  private final Cache<String, Optional<ClientDomainConfig>> byHostname;
  private final Cache<UUID, Optional<ClientDomainConfig>> byOauthClientId;

  /* package */ CachingClientDomainConfigRepository(
      @Qualifier("jpaClientDomainConfigRepository") final ClientDomainConfigRepository delegate,
      @Value("${clavaris.client-domain.config-cache-ttl-seconds:30}") final long ttlSeconds,
      // TD-PERF-006: same order-of-magnitude reasoning as CachingRateLimitPolicyRepository's own
      // identical property — real OAuthClient count (and therefore distinct hostnames) is
      // expected to stay small for the foreseeable future; 10,000 is generous headroom, not a
      // number this project expects to actually approach.
      @Value("${clavaris.client-domain.config-cache-max-size:10000}") final long maxSize) {
    this.delegate = delegate;
    final Duration ttl = Duration.ofSeconds(ttlSeconds);
    this.byHostname = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(maxSize).build();
    this.byOauthClientId = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(maxSize).build();
  }

  @Override
  public Optional<ClientDomainConfig> findByOAuthClientId(final UUID oauthClientId) {
    return byOauthClientId.get(oauthClientId, delegate::findByOAuthClientId);
  }

  @Override
  public Optional<ClientDomainConfig> findByHostname(final String hostname) {
    return byHostname.get(hostname, delegate::findByHostname);
  }

  // Write-through on both keys, not merely invalidate-and-let-the-next-read-repopulate — same
  // "an operator's own change must apply immediately" rationale
  // CachingRateLimitPolicyRepository#save's own identical comment documents. A hostname change
  // (reRequest with a new hostname) leaves the OLD hostname's cache entry to expire/get evicted on
  // its own rather than being explicitly evicted — same bounded-staleness trade-off as before, now
  // additionally bounded by size, not just time; the old hostname is no longer claimed by this
  // row's own oauthClientId once ownership moves, so a stale hit there would only ever resolve to
  // a row that no longer points at it once re-read.
  @Override
  public void save(final ClientDomainConfig config) {
    delegate.save(config);
    final Optional<ClientDomainConfig> fresh = Optional.of(config);
    byOauthClientId.put(config.oauthClientId(), fresh);
    config.hostname().ifPresent(hostname -> byHostname.put(hostname, fresh));
  }

  // Test-only observability, not used by any production code path — same rationale
  // CachingRateLimitPolicyRepository's own identical method documents.
  /* package */ long byOauthClientIdEstimatedSizeAfterCleanup() {
    byOauthClientId.cleanUp();
    return byOauthClientId.estimatedSize();
  }
}
