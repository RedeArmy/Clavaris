package com.clavaris.identity.application.usecase.confirmplatformaccountpasswordreset;

import com.clavaris.identity.domain.model.PlatformAccountId;

/**
 * Outbound port — implemented in {@code app} (bridging to Spring Security's {@code
 * SessionRegistry}), same "identity-module never depends on spring-security-config" rationale as
 * {@code confirmpasswordreset}'s own {@code AccountTokenRevoker}. ADR-0012: {@code PlatformAccount}
 * login is a plain authenticated {@code HttpSession}, not an OAuth refresh-token chain — so
 * BR-ID-04's "assume prior sessions compromised" equivalent here is expiring every {@code
 * HttpSession} Spring Security's own {@code SessionRegistry} knows about for this principal, not a
 * custom token-revocation cascade.
 */
@FunctionalInterface
public interface PlatformAccountSessionRevoker {

  void revokeAllSessionsFor(PlatformAccountId platformAccountId);
}
