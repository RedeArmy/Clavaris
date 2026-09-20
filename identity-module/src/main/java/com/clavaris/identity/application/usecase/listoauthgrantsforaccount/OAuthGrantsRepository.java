package com.clavaris.identity.application.usecase.listoauthgrantsforaccount;

import com.clavaris.identity.domain.model.AccountId;
import java.util.List;

/**
 * Outbound port — implemented in {@code app} against raw {@code oauth2_authorization} (TD-SEC-003),
 * same reason {@link
 * com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountTokenRevoker} is: identity-
 * module must never depend on Spring Authorization Server or its table. Unlike that port, this one
 * also needs {@code client-registry-module}'s {@code OAuthClientRepository} to resolve each row's
 * display {@code clientId} — the bridge implementation is where that cross-module join happens
 * (both modules are already {@code app}'s own dependencies), never inside identity-module itself.
 */
public interface OAuthGrantsRepository {

  List<OAuthGrant> findByAccountId(AccountId accountId);

  /**
   * Deletes the row only if it both exists and belongs to {@code accountId} — the ownership check
   * happens in the same statement, not as a separate lookup, same "can only ever act on the
   * caller's own resource" posture {@code AccountActiveSessionsRepository#revoke} already has.
   *
   * @return {@code true} if a row was deleted, {@code false} if no matching row existed (a benign
   *     race, or a mismatched/foreign {@code authorizationId} — the caller cannot tell which, by
   *     design).
   */
  boolean revokeById(AccountId accountId, String authorizationId);
}
