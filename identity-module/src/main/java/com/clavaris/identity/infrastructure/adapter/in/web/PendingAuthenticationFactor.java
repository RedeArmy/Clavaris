package com.clavaris.identity.infrastructure.adapter.in.web;

/**
 * ADR-0024 §6: which {@link AuthenticatedSessionEstablisher} method a device-trust or session-task
 * challenge must resume with once it's cleared — the primary factor already succeeded before the
 * challenge was interposed, so this is only ever a record of *which* establish call to finish,
 * never a second factor in its own right.
 *
 * <p><b>TD-SEC-055 (fixed):</b> {@code SOCIAL} used to be absent here, on the theory that ADR-0024
 * "deliberately" scoped Device Trust to the four password/email-code/email-link controllers only —
 * re-reading that ADR found no actual decision to exclude social login, only that it predates the
 * ADR and was never addressed by it. In the meantime {@code app}'s own {@code
 * SocialLoginAuthenticationSuccessHandler} established a session directly, through neither {@link
 * DeviceTrustGate} nor {@link SessionTaskGate} — a real, live bypass of both a device-trust step-up
 * and an operator-forced password reset for any account with a linked social identity. This class,
 * {@link DeviceTrustGate#intercept}, {@link SessionTaskGate#intercept}, and {@link
 * AuthenticatedSessionCompletion#complete} all widened to public/accept an optional {@code
 * SocialProvider} so {@code app} can call the same two gates before establishing a social session.
 */
// PMD.LongVariable: ONE_TIME_EMAIL_PROOF names the OIDC amr=otp factor precisely — see this
// enum's own Javadoc; a shortened name would only make the two constants harder to tell apart.
@SuppressWarnings("PMD.LongVariable")
public enum PendingAuthenticationFactor {

  /** {@link LoginController} (email+password) and {@link UsernameSignInController}. */
  PASSWORD,

  /** {@link EmailCodeSignInController} and {@link EmailLinkSignInController}. */
  ONE_TIME_EMAIL_PROOF,

  /** {@code app}'s {@code SocialLoginAuthenticationSuccessHandler} (Google/GitHub, ADR-0020). */
  SOCIAL
}
