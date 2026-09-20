package com.clavaris.identity.application.usecase.listoauthgrantsforaccount;

import java.util.Comparator;
import java.util.List;

/** Orchestration for {@link ListOAuthGrantsForAccountUseCase} — a thin, read-only delegate. */
public class ListOAuthGrantsForAccountService implements ListOAuthGrantsForAccountUseCase {

  // Nulls (a grant whose access/refresh token already expired without a replacement) sort last,
  // not first — most-recently-active grant belongs at the top of the admin table.
  private static final Comparator<OAuthGrant> RECENT_FIRST =
      Comparator.comparing(
          OAuthGrant::lastIssuedAt, Comparator.nullsLast(Comparator.reverseOrder()));

  private final OAuthGrantsRepository grants;

  public ListOAuthGrantsForAccountService(final OAuthGrantsRepository grants) {
    this.grants = grants;
  }

  @Override
  public List<OAuthGrant> handle(final ListOAuthGrantsForAccountQuery query) {
    return grants.findByAccountId(query.accountId()).stream().sorted(RECENT_FIRST).toList();
  }
}
