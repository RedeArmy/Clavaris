package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.ClientDomainConfigRepository;
import com.clavaris.clientregistry.domain.model.ClientDomainConfig;
import com.clavaris.clientregistry.domain.model.ClientDomainMode;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ADR-0009 §4: proves the caching decorator itself, not the real Postgres round trip — that's
 * already covered by {@code JpaClientDomainConfigRepositoryTest} for the delegate this class wraps.
 * Mirrors {@code CachingRateLimitPolicyRepositoryTest}'s own exact shape.
 */
class CachingClientDomainConfigRepositoryTest {

  private static final long TTL_SECONDS = 30;
  private static final long MAX_SIZE = 10_000;

  @Test
  void aSecondReadByHostnameWithinTheTtlNeverReachesTheDelegate() {
    ClientDomainConfigRepository delegate = mock(ClientDomainConfigRepository.class);
    ClientDomainConfig config = aVerifiedConfig("login.example.com");
    when(delegate.findByHostname("login.example.com")).thenReturn(Optional.of(config));
    CachingClientDomainConfigRepository cache =
        new CachingClientDomainConfigRepository(delegate, TTL_SECONDS, MAX_SIZE);

    cache.findByHostname("login.example.com");
    Optional<ClientDomainConfig> second = cache.findByHostname("login.example.com");

    assertThat(second).contains(config);
    verify(delegate, times(1)).findByHostname("login.example.com");
  }

  @Test
  void anAbsentDomainIsAlsoCachedNotJustAConfiguredOne() {
    ClientDomainConfigRepository delegate = mock(ClientDomainConfigRepository.class);
    when(delegate.findByHostname("unclaimed.example.com")).thenReturn(Optional.empty());
    CachingClientDomainConfigRepository cache =
        new CachingClientDomainConfigRepository(delegate, TTL_SECONDS, MAX_SIZE);

    cache.findByHostname("unclaimed.example.com");
    Optional<ClientDomainConfig> second = cache.findByHostname("unclaimed.example.com");

    assertThat(second).isEmpty();
    verify(delegate, times(1)).findByHostname("unclaimed.example.com");
  }

  @Test
  void twoDifferentHostnamesAreCachedIndependently() {
    ClientDomainConfigRepository delegate = mock(ClientDomainConfigRepository.class);
    when(delegate.findByHostname("a.example.com"))
        .thenReturn(Optional.of(aVerifiedConfig("a.example.com")));
    when(delegate.findByHostname("b.example.com"))
        .thenReturn(Optional.of(aVerifiedConfig("b.example.com")));
    CachingClientDomainConfigRepository cache =
        new CachingClientDomainConfigRepository(delegate, TTL_SECONDS, MAX_SIZE);

    cache.findByHostname("a.example.com");
    cache.findByHostname("b.example.com");

    verify(delegate, times(1)).findByHostname("a.example.com");
    verify(delegate, times(1)).findByHostname("b.example.com");
  }

  @Test
  void hostnameAndOauthClientIdCachesAreIndependent() {
    ClientDomainConfigRepository delegate = mock(ClientDomainConfigRepository.class);
    UUID oauthClientId = UUID.randomUUID();
    ClientDomainConfig config =
        ClientDomainConfig.request(
            oauthClientId, ClientDomainMode.CNAME, "login.example.com", null);
    when(delegate.findByOAuthClientId(oauthClientId)).thenReturn(Optional.of(config));
    CachingClientDomainConfigRepository cache =
        new CachingClientDomainConfigRepository(delegate, TTL_SECONDS, MAX_SIZE);

    cache.findByOAuthClientId(oauthClientId);
    cache.findByOAuthClientId(oauthClientId);

    verify(delegate, times(1)).findByOAuthClientId(oauthClientId);
    verify(delegate, never()).findByHostname(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void anExpiredEntryFallsThroughToTheDelegateAgain() {
    ClientDomainConfigRepository delegate = mock(ClientDomainConfigRepository.class);
    when(delegate.findByHostname("login.example.com"))
        .thenReturn(Optional.of(aVerifiedConfig("login.example.com")));
    // A zero-second TTL means every entry is already expired the instant it's written — the
    // simplest deterministic way to prove the fall-through path without sleeping in a test.
    CachingClientDomainConfigRepository cache =
        new CachingClientDomainConfigRepository(delegate, 0, MAX_SIZE);

    cache.findByHostname("login.example.com");
    cache.findByHostname("login.example.com");

    verify(delegate, times(2)).findByHostname("login.example.com");
  }

  // ADR-0009 §4's own accepted design: a write updates both cache keys immediately
  // (write-through), so an operator's domain request/verification is never masked by a stale
  // cached read for up to `ttl` afterward — the same rationale
  // CachingRateLimitPolicyRepositoryTest's own identical test documents.
  @Test
  void saveThroughEitherCacheThenReadsBackByBothKeysWithoutReachingTheDelegate() {
    ClientDomainConfigRepository delegate = mock(ClientDomainConfigRepository.class);
    ClientDomainConfig config = aVerifiedConfig("login.example.com");
    CachingClientDomainConfigRepository cache =
        new CachingClientDomainConfigRepository(delegate, TTL_SECONDS, MAX_SIZE);

    cache.save(config);
    Optional<ClientDomainConfig> byHostname = cache.findByHostname("login.example.com");
    Optional<ClientDomainConfig> byOauthClientId =
        cache.findByOAuthClientId(config.oauthClientId());

    assertThat(byHostname).contains(config);
    assertThat(byOauthClientId).contains(config);
    verify(delegate, never()).findByHostname(org.mockito.ArgumentMatchers.any());
    verify(delegate, never()).findByOAuthClientId(org.mockito.ArgumentMatchers.any());
  }

  // TD-PERF-006: the actual fix this row asked for — a real, bounded cache, not an unbounded
  // ConcurrentHashMap. A tiny maxSize (2) and more distinct OAuthClients than that fits proves
  // Caffeine's own eviction genuinely runs, not just that the API compiles.
  @Test
  void cacheSizeStaysBoundedByMaxSizeNotUnbounded() {
    ClientDomainConfigRepository delegate = mock(ClientDomainConfigRepository.class);
    long smallMaxSize = 2;
    CachingClientDomainConfigRepository cache =
        new CachingClientDomainConfigRepository(delegate, TTL_SECONDS, smallMaxSize);
    for (int i = 0; i < 10; i++) {
      cache.save(aVerifiedConfig("host-" + i + ".example.com"));
    }

    assertThat(cache.byOauthClientIdEstimatedSizeAfterCleanup()).isLessThanOrEqualTo(smallMaxSize);
  }

  @Test
  void saveAlwaysDelegatesTheRealWrite() {
    ClientDomainConfigRepository delegate = mock(ClientDomainConfigRepository.class);
    ClientDomainConfig config = aVerifiedConfig("login.example.com");
    CachingClientDomainConfigRepository cache =
        new CachingClientDomainConfigRepository(delegate, TTL_SECONDS, MAX_SIZE);

    cache.save(config);

    verify(delegate).save(config);
  }

  private static ClientDomainConfig aVerifiedConfig(final String hostname) {
    return ClientDomainConfig.request(UUID.randomUUID(), ClientDomainMode.CNAME, hostname, null)
        .markVerified();
  }
}
