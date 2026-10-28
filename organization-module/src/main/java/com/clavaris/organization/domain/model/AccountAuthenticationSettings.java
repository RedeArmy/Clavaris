package com.clavaris.organization.domain.model;

import java.util.Objects;

/**
 * The ten sign-up/sign-in options an operator tunes together for one Organization (ADR-0024,
 * TD-FUT-019) — the one place a parameter object pays (TD-ARCH-042): {@link
 * AccountAuthenticationPolicy#define}/{@link AccountAuthenticationPolicy#withPolicy} used to take
 * these as ten positional values, nine of them booleans a caller could swap without the compiler
 * noticing. The defaults are exactly this codebase's behaviour before the feature existed (see
 * {@link AccountAuthenticationPolicy}). {@code selfServiceEmailChangeEnabled} (TD-FUT-052) is not
 * here: it is switched on its own, by {@link
 * AccountAuthenticationPolicy#withSelfServiceEmailChange}.
 */
// PMD.LongVariable: each name spells out exactly which option it governs, the convention every
// sibling policy in this module follows.
@SuppressWarnings("PMD.LongVariable")
public record AccountAuthenticationSettings(
    boolean emailVerificationRequiredAtSignIn,
    EmailVerificationMethod emailVerificationMethod,
    boolean emailCodeSignInEnabled,
    boolean emailLinkSignInEnabled,
    boolean usernameSignUpEnabled,
    boolean usernameRequired,
    boolean usernameSignInEnabled,
    boolean passwordAtSignUpEnabled,
    boolean deviceTrustEnabled,
    boolean selfRegistrationRequiresApproval) {

  public AccountAuthenticationSettings {
    Objects.requireNonNull(emailVerificationMethod, "emailVerificationMethod must not be null");
  }

  /** What an Organization has until an operator tunes anything. */
  public static AccountAuthenticationSettings defaults() {
    return new AccountAuthenticationSettings(
        false, EmailVerificationMethod.LINK, false, false, false, false, false, true, false, false);
  }
}
