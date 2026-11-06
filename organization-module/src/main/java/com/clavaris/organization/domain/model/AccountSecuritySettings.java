package com.clavaris.organization.domain.model;

/**
 * TD-ARCH-046: the per-{@code Organization} switches that sit beside {@link
 * AccountAuthenticationSettings}'s sign-up and sign-in options: whether an End-User may change
 * their own email, whether a password sign-in must also present an emailed code, and whether the
 * authenticator app, passkeys and the passkey offer are available.
 *
 * <p>They are grouped, as the sign-up and sign-in options are, because five booleans of one type in
 * a row are a constructor in which two can be swapped without the compiler or a test that sets both
 * noticing, and every new switch used to edit every {@code withX} method, the reconstitution and
 * two command constructors. Here each switch is changed by a method of its own name and the rest
 * are carried along untouched.
 *
 * <p>Every default is chosen to match this codebase's behaviour before the switch existed, the same
 * "opt-in, zero regression" posture as the rest of the policy: an Organization nobody has tuned
 * behaves exactly as it did.
 */
public record AccountSecuritySettings(
    boolean selfServiceEmailChangeEnabled,
    boolean codeAfterPasswordRequired,
    boolean totpEnabled,
    boolean passkeysEnabled,
    boolean passkeyOfferEnabled) {

  /** Email change on (TD-FUT-052), no emailed code after a password, the app off, passkeys on. */
  public static AccountSecuritySettings defaults() {
    return new AccountSecuritySettings(true, false, false, true, true);
  }

  /** TD-FUT-052: whether an End-User may change their own email. */
  public AccountSecuritySettings withSelfServiceEmailChange(final boolean enabled) {
    return new AccountSecuritySettings(
        enabled, codeAfterPasswordRequired, totpEnabled, passkeysEnabled, passkeyOfferEnabled);
  }

  /** ADR-0035: whether a password sign-in must also present an emailed code, every time. */
  public AccountSecuritySettings withCodeAfterPasswordRequired(final boolean required) {
    return new AccountSecuritySettings(
        selfServiceEmailChangeEnabled, required, totpEnabled, passkeysEnabled, passkeyOfferEnabled);
  }

  /** ADR-0040: whether accounts may enrol an authenticator app. */
  public AccountSecuritySettings withTotpEnabled(final boolean enabled) {
    return new AccountSecuritySettings(
        selfServiceEmailChangeEnabled,
        codeAfterPasswordRequired,
        enabled,
        passkeysEnabled,
        passkeyOfferEnabled);
  }

  /** ADR-0041: whether accounts may use passkeys at all. */
  public AccountSecuritySettings withPasskeysEnabled(final boolean enabled) {
    return new AccountSecuritySettings(
        selfServiceEmailChangeEnabled,
        codeAfterPasswordRequired,
        totpEnabled,
        enabled,
        passkeyOfferEnabled);
  }

  /** ADR-0041: whether a sign-in by emailed code or link is followed by the passkey offer. */
  public AccountSecuritySettings withPasskeyOfferEnabled(final boolean enabled) {
    return new AccountSecuritySettings(
        selfServiceEmailChangeEnabled,
        codeAfterPasswordRequired,
        totpEnabled,
        passkeysEnabled,
        enabled);
  }
}
