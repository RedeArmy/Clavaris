package com.clavaris.identity.application.usecase.rotaterefreshtoken;

import com.clavaris.identity.domain.model.AccountId;

/**
 * Outbound port — implemented in {@code app} (bridging to Spring Security's {@code
 * SessionRegistry}), same "identity-module never depends on spring-security-config" rationale as
 * this package's own {@link AccountTokenRevoker}.
 *
 * <p>TD-SEC-031 (SDE-III review, 2026-08-26): every other revocation cascade in this module (this
 * class's own BR-ID-03 reuse response, {@code confirmpasswordreset}'s BR-ID-04 reset, {@code
 * deleteaccount}'s BR-DATA-02/03 hard delete) already revoked the SAS-managed access/ID token and
 * the domain-owned {@code Session}/{@code RefreshToken} rows, but none of them ever touched the one
 * artifact that actually keeps a browser "logged in" across the hosted login page's own interactive
 * Authorization Code + PKCE flow — the Spring-Security {@code HttpSession} itself ({@code
 * OrganizationAuthorizationServerConfig}'s {@code HttpSessionSecurityContextRepository}). Without
 * this, a stale-but-still-live browser session could mint a fresh authorization code (and from it a
 * fresh token) for an account whose credential/tokens were just reset or whose row was just
 * hard-deleted — the exact gap {@code PlatformAccountSessionRevoker} already closed for the
 * platform tier (ADR-0012).
 */
@FunctionalInterface
public interface AccountSessionRevoker {

  void revokeAllSessionsFor(AccountId accountId);
}
