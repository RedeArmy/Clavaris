package com.clavaris.identity.domain.service;

/**
 * Security/correctness finding, 2026-10-08: real email/code delivery (verification links/codes,
 * password reset, passwordless sign-in, device-trust challenges) must work identically whether the
 * owning {@code Organization} is {@code DEVELOPMENT} or {@code PRODUCTION} — Clerk's own
 * Development instances send real mail to real addresses too, through Clerk's own shared sending
 * infrastructure; only a specifically-marked {@code +clerk_test} address gets the fixed bypass code
 * instead of a real send. The {@code OrganizationEnvironment}-wide bypass this class replaces
 * (blanket-skipping every real send for an entire {@code DEVELOPMENT} Organization) over-applied
 * that distinction: a real person registering with a real address against a sandboxed Organization
 * — exactly what every Organization defaults to at creation — never received a real verification
 * email at all, with no way to complete sign-up. This class is the narrow, per-address replacement:
 * only an address carrying this marker is ever treated as a test fixture, in any environment.
 *
 * <p>{@code +clavaris_test} mirrors Clerk's own {@code +clerk_test} convention exactly (a plus-
 * addressing tag in the local part, before the {@code @}) — any mail provider that honors RFC 5233
 * sub-addressing still delivers a real send to a marked address if one were ever sent, but every
 * {@code RequestEmail*}/{@code RequestDeviceTrustChallenge} use case checks this first and skips
 * the real send entirely, the same "no real email/SMS dispatched, token still issued and
 * completable" contract the old Organization-wide bypass already established — only the trigger
 * condition changed, not what happens once triggered.
 */
public final class TestEmailAddress {

  private static final String TEST_MARKER = "+clavaris_test";

  private TestEmailAddress() {
    // Static utility — not instantiable, same convention as EmailOneTimeCode/RefreshTokenSecret.
  }

  /**
   * {@code rawEmail} is expected already-normalized (lower-cased) — every real caller passes {@code
   * Account.email().value()}, which {@link com.clavaris.identity.domain.model.Email}'s own
   * constructor already normalizes; this does not re-normalize so a caller that bypasses that
   * normalization cannot silently defeat the check by casing the marker differently.
   */
  public static boolean isTestAddress(final String rawEmail) {
    final int at = rawEmail.indexOf('@');
    final String localPart = at < 0 ? rawEmail : rawEmail.substring(0, at);
    return localPart.contains(TEST_MARKER);
  }
}
