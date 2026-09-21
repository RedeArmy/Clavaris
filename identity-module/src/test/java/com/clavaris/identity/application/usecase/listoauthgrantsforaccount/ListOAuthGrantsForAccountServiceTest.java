package com.clavaris.identity.application.usecase.listoauthgrantsforaccount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.identity.domain.model.AccountId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ListOAuthGrantsForAccountServiceTest {

  private OAuthGrantsRepository grants;
  private ListOAuthGrantsForAccountService service;
  private AccountId accountId;

  @BeforeEach
  void setUp() {
    grants = mock(OAuthGrantsRepository.class);
    service = new ListOAuthGrantsForAccountService(grants);
    accountId = AccountId.newId();
  }

  @Test
  void sortsGrantsByMostRecentlyIssuedFirst() {
    final OAuthGrant older = grantIssuedAt(Instant.parse("2026-01-01T00:00:00Z"));
    final OAuthGrant newer = grantIssuedAt(Instant.parse("2026-06-01T00:00:00Z"));
    final OAuthGrant neverRefreshed = grantIssuedAt(null);
    when(grants.findByAccountId(accountId)).thenReturn(List.of(older, newer, neverRefreshed));

    final List<OAuthGrant> result = service.handle(new ListOAuthGrantsForAccountQuery(accountId));

    assertThat(result).containsExactly(newer, older, neverRefreshed);
  }

  @Test
  void returnsAnEmptyListWhenTheAccountHasNoGrants() {
    when(grants.findByAccountId(accountId)).thenReturn(List.of());

    assertThat(service.handle(new ListOAuthGrantsForAccountQuery(accountId))).isEmpty();
  }

  private static OAuthGrant grantIssuedAt(final Instant issuedAt) {
    return new OAuthGrant(
        "auth-id", "jobseeker-web", "authorization_code", "openid profile", issuedAt, null);
  }
}
