package com.clavaris.identity.infrastructure.adapter.in.web;

/**
 * ADR-0024 §6: which {@link AuthenticatedSessionEstablisher} method a device-trust challenge must
 * resume with once the step-up code is confirmed — the primary factor already succeeded before the
 * challenge was interposed, so this is only ever a record of *which* establish call to finish,
 * never a second factor in its own right. Social login is deliberately not a member here: {@link
 * SocialLoginRedirectController}'s own flow is not retrofitted with device trust in v1 (see the
 * ADR's own scope note).
 */
// PMD.LongVariable: ONE_TIME_EMAIL_PROOF names the OIDC amr=otp factor precisely — see this
// enum's own Javadoc; a shortened name would only make the two constants harder to tell apart.
@SuppressWarnings("PMD.LongVariable")
/* package */ enum PendingAuthenticationFactor {

  /** {@link LoginController} (email+password) and {@link UsernameSignInController}. */
  PASSWORD,

  /** {@link EmailCodeSignInController} and {@link EmailLinkSignInController}. */
  ONE_TIME_EMAIL_PROOF
}
