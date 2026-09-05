package com.clavaris.organization.domain.model;

/**
 * ADR-0024 (sign-up/sign-in options, Clerk parity): which mechanism(s) {@code
 * RequestEmailVerificationService} (identity-module) offers when issuing a verification token —
 * {@code LINK} is this codebase's original, only-ever-existed mechanism (a clickable, single-use
 * URL); {@code CODE} is the new short numeric one-time code, mirroring Clerk's own second
 * verification-method option; {@code BOTH} sends one email carrying both, letting the account
 * holder use whichever is more convenient for that request.
 *
 * <p>Plain enum, not a {@code List&lt;String&gt;} the way {@code allowedSocialProviders} is —
 * unlike social providers (an open, extensible set), verification method is a fixed three-way
 * choice, so an enum is the correct, simpler shape here, not an inconsistency with that other
 * field.
 */
public enum EmailVerificationMethod {
  LINK,
  CODE,
  BOTH
}
