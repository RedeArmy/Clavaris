package com.clavaris.identity.application.usecase.rotaterefreshtoken;

import com.clavaris.identity.domain.model.AccountId;

/**
 * Outbound port — implemented in {@code app} (bridging to raw SQL against {@code
 * oauth2_authorization}, TD-SEC-003), for the same reason {@code SigningKeyProvisioner}/{@code
 * AuthenticatedSessionEstablisher} are implemented there: identity-module must never depend on
 * Spring Authorization Server types. {@link com.clavaris.identity.domain.model.RefreshToken}/{@code
 * Session} revocation (BR-ID-03's own tables) is handled entirely within identity-module by {@link
 * com.clavaris.identity.application.usecase.issuerefreshtoken.RefreshTokenRepository}/{@code
 * SessionRepository} — this port exists only for the access/ID tokens SAS itself tracks, which
 * BR-ID-03 also requires revoked ("every active token for that account, not just the reused one").
 */
@FunctionalInterface
public interface AccountTokenRevoker {

  void revokeAllTokensFor(AccountId accountId);
}
