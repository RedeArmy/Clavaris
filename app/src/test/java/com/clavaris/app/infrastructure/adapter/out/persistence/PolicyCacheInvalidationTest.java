package com.clavaris.app.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.ClientDomainConfigRepository;
import com.clavaris.clientregistry.domain.model.ClientDomainConfig;
import com.clavaris.clientregistry.domain.model.ClientDomainMode;
import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionPolicyProvider;
import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionPolicySnapshot;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicySnapshot;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization.AccountAuthenticationPolicyRepository;
import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.RateLimitPolicyRepository;
import com.clavaris.organization.application.usecase.setsessionpolicyfororganization.SessionPolicyRepository;
import com.clavaris.organization.domain.model.AccountAuthenticationPolicy;
import com.clavaris.organization.domain.model.RateLimitPolicy;
import com.clavaris.organization.domain.model.SessionPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * TD-ARCH-049 without Redis: how each cache reacts to being told to drop an entry, and what each
 * writer announces and when (after the commit, never before it).
 */
class PolicyCacheInvalidationTest {

  private static final long TTL = 3600;
  private static final long MAX = 100;

  /** Remembers what was announced to the others, and lets a test run the "after commit" work. */
  private static final class RecordingBus implements CacheInvalidationBus {
    private final InProcessCacheInvalidationBus local = new InProcessCacheInvalidationBus();
    final List<String> toOthers = new ArrayList<>();
    final List<String> evictedEverywhere = new ArrayList<>();

    @Override
    public void subscribe(final String cache, final Consumer<String> evict) {
      local.subscribe(cache, evict);
    }

    @Override
    public void invalidate(final String cache, final String key) {
      evictedEverywhere.add(cache + "=" + key);
      local.invalidate(cache, key);
    }

    @Override
    public void invalidateOthers(final String cache, final String key) {
      toOthers.add(cache + "=" + key);
    }
  }

  /** An {@code AfterCommitExecutor} that holds the work until the test "commits". */
  private static final class DeferredCommit
      implements com.clavaris.common.application.port.AfterCommitExecutor {
    private final List<Runnable> pending = new ArrayList<>();

    @Override
    public void run(final Runnable action) {
      pending.add(action);
    }

    void commit() {
      pending.forEach(Runnable::run);
      pending.clear();
    }
  }

  @Test
  void aToldToDropSessionPolicyIsReadAgainFromTheSource() {
    SessionPolicyProvider delegate = mock(SessionPolicyProvider.class);
    OrganizationId org = new OrganizationId(UUID.randomUUID());
    when(delegate.policyFor(org)).thenReturn(SessionPolicySnapshot.defaults());
    RecordingBus bus = new RecordingBus();
    CachingSessionPolicyProvider cache = new CachingSessionPolicyProvider(delegate, TTL, MAX, bus);

    cache.policyFor(org);
    cache.policyFor(org);
    verify(delegate, times(1)).policyFor(org);
    bus.invalidate(CacheInvalidationBus.SESSION_POLICY, org.value().toString());
    cache.policyFor(org);

    verify(delegate, times(2)).policyFor(org);
  }

  @Test
  void aToldToDropAccountAuthenticationPolicyIsReadAgainFromTheSource() {
    AccountAuthenticationPolicyProvider delegate = mock(AccountAuthenticationPolicyProvider.class);
    OrganizationId org = new OrganizationId(UUID.randomUUID());
    when(delegate.policyFor(org)).thenReturn(AccountAuthenticationPolicySnapshot.defaults());
    RecordingBus bus = new RecordingBus();
    CachingAccountAuthenticationPolicyProvider cache =
        new CachingAccountAuthenticationPolicyProvider(delegate, TTL, MAX, bus);

    cache.policyFor(org);
    bus.invalidate(CacheInvalidationBus.ACCOUNT_AUTHENTICATION_POLICY, org.value().toString());
    cache.policyFor(org);

    verify(delegate, times(2)).policyFor(org);
  }

  @Test
  void aToldToDropRateLimitPolicyIsReadAgainAndASaveAnnouncesToTheOthersOnlyAfterTheCommit() {
    RateLimitPolicyRepository delegate = mock(RateLimitPolicyRepository.class);
    UUID org = UUID.randomUUID();
    Instant now = Instant.now();
    RateLimitPolicy policy = RateLimitPolicy.reconstitute(UUID.randomUUID(), org, 100, now, now);
    when(delegate.findByOrganizationId(org)).thenReturn(Optional.of(policy));
    RecordingBus bus = new RecordingBus();
    DeferredCommit commit = new DeferredCommit();
    CachingRateLimitPolicyRepository cache =
        new CachingRateLimitPolicyRepository(delegate, TTL, MAX, bus, commit);

    cache.findByOrganizationId(org);
    bus.invalidate(CacheInvalidationBus.RATE_LIMIT_POLICY, org.toString());
    cache.findByOrganizationId(org);
    verify(delegate, times(2)).findByOrganizationId(org);

    cache.save(policy);
    assertThat(bus.toOthers).as("nothing is announced inside the transaction").isEmpty();
    commit.commit();
    assertThat(bus.toOthers).containsExactly(CacheInvalidationBus.RATE_LIMIT_POLICY + "=" + org);
    // The writer put the new value itself, so it was not told to drop it.
    assertThat(bus.evictedEverywhere).hasSize(1);
  }

  @Test
  void aToldToDropClientDomainConfigIsReadAgainAndASaveAnnouncesBothKeysAfterTheCommit() {
    ClientDomainConfigRepository delegate = mock(ClientDomainConfigRepository.class);
    UUID client = UUID.randomUUID();
    ClientDomainConfig config =
        ClientDomainConfig.request(
            client, ClientDomainMode.CNAME, "login.example.com", null, Instant.now());
    when(delegate.findByOAuthClientId(client)).thenReturn(Optional.of(config));
    when(delegate.findByHostname("login.example.com")).thenReturn(Optional.of(config));
    RecordingBus bus = new RecordingBus();
    DeferredCommit commit = new DeferredCommit();
    CachingClientDomainConfigRepository cache =
        new CachingClientDomainConfigRepository(delegate, TTL, MAX, bus, commit);

    cache.findByOAuthClientId(client);
    cache.findByHostname("login.example.com");
    bus.invalidate(CacheInvalidationBus.CLIENT_DOMAIN_BY_CLIENT, client.toString());
    bus.invalidate(CacheInvalidationBus.CLIENT_DOMAIN_BY_HOSTNAME, "login.example.com");
    cache.findByOAuthClientId(client);
    cache.findByHostname("login.example.com");
    verify(delegate, times(2)).findByOAuthClientId(client);
    verify(delegate, times(2)).findByHostname("login.example.com");

    cache.save(config);
    assertThat(bus.toOthers).isEmpty();
    commit.commit();
    assertThat(bus.toOthers)
        .containsExactlyInAnyOrder(
            CacheInvalidationBus.CLIENT_DOMAIN_BY_CLIENT + "=" + client,
            CacheInvalidationBus.CLIENT_DOMAIN_BY_HOSTNAME + "=login.example.com");
  }

  @Test
  void theSessionPolicyWriterSavesThenInvalidatesEverywhereOnlyAfterTheCommit() {
    SessionPolicyRepository delegate = mock(SessionPolicyRepository.class);
    UUID org = UUID.randomUUID();
    SessionPolicy policy = SessionPolicy.define(org, 100, 10, 5, true);
    RecordingBus bus = new RecordingBus();
    DeferredCommit commit = new DeferredCommit();
    InvalidatingSessionPolicyRepository writer =
        new InvalidatingSessionPolicyRepository(delegate, bus, commit);

    writer.save(policy);
    verify(delegate).save(policy);
    assertThat(bus.evictedEverywhere).isEmpty();
    commit.commit();

    assertThat(bus.evictedEverywhere)
        .containsExactly(CacheInvalidationBus.SESSION_POLICY + "=" + org);
  }

  @Test
  void theSessionPolicyWriterReadsStraightThrough() {
    SessionPolicyRepository delegate = mock(SessionPolicyRepository.class);
    UUID org = UUID.randomUUID();
    SessionPolicy policy = SessionPolicy.define(org, 100, 10, 5, true);
    when(delegate.findByOrganizationId(org)).thenReturn(Optional.of(policy));

    assertThat(
            new InvalidatingSessionPolicyRepository(
                    delegate, new RecordingBus(), new DeferredCommit())
                .findByOrganizationId(org))
        .contains(policy);
  }

  @Test
  void theAccountAuthenticationPolicyWriterSavesThenInvalidatesEverywhereAfterTheCommit() {
    AccountAuthenticationPolicyRepository delegate =
        mock(AccountAuthenticationPolicyRepository.class);
    UUID org = UUID.randomUUID();
    AccountAuthenticationPolicy policy = AccountAuthenticationPolicy.defaults(org);
    when(delegate.findByOrganizationId(org)).thenReturn(Optional.of(policy));
    RecordingBus bus = new RecordingBus();
    DeferredCommit commit = new DeferredCommit();
    InvalidatingAccountAuthenticationPolicyRepository writer =
        new InvalidatingAccountAuthenticationPolicyRepository(delegate, bus, commit);

    writer.save(policy);
    verify(delegate).save(policy);
    assertThat(bus.evictedEverywhere).isEmpty();
    commit.commit();

    assertThat(bus.evictedEverywhere)
        .containsExactly(CacheInvalidationBus.ACCOUNT_AUTHENTICATION_POLICY + "=" + org);
    assertThat(writer.findByOrganizationId(org)).contains(policy);
  }

  @Test
  void theInProcessBusReachesOnlyThisProcess() {
    InProcessCacheInvalidationBus bus = new InProcessCacheInvalidationBus();
    List<String> heard = new ArrayList<>();
    bus.subscribe("c", heard::add);

    bus.invalidateOthers("c", "ignored");
    bus.invalidate("c", "dropped");
    bus.invalidate("other-cache", "nobody");

    assertThat(heard).containsExactly("dropped");
  }
}
