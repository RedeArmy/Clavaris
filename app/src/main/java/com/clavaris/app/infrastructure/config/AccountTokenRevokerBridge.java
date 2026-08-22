package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountTokenRevoker;
import com.clavaris.identity.domain.model.AccountId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Implements identity-module's outbound port — the bridge lives in {@code app}, not
 * identity-module, for the same reason {@code CreateOrganizationSigningKeyBridge}'s implementation
 * does: identity-module must never depend on Spring Authorization Server or its {@code
 * oauth2_authorization} table (TD-SEC-003).
 *
 * <p>Raw {@link JdbcTemplate}, not {@code OAuth2AuthorizationService}: that interface (({@code
 * save}/{@code remove}/{@code findById}/{@code findByToken})) has no "find every authorization for
 * this principal" operation — confirmed by reading its own four-method contract. BR-ID-03's "revoke
 * every active token for that account" requirement has no other way to reach the abstraction, so
 * this deliberately steps outside it, the same class of escape hatch already used and documented
 * for TD-SEC-019. {@code principal_name} is set to {@code accountId.toString()} at login time
 * ({@code SpringSecurityAuthenticatedSessionEstablisher}) — a random UUID, so no cross-Organization
 * collision risk exists in practice (same reasoning already applied to {@code SigningKeyStore}'s
 * shared-file kid aliasing).
 */
@Component
class AccountTokenRevokerBridge implements AccountTokenRevoker {

  private final JdbcTemplate jdbcTemplate;

  /* package */ AccountTokenRevokerBridge(final JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void revokeAllTokensFor(final AccountId accountId) {
    jdbcTemplate.update(
        "delete from oauth2_authorization where principal_name = ?", accountId.value().toString());
  }
}
