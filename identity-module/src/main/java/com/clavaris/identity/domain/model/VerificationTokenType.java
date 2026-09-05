package com.clavaris.identity.domain.model;

/**
 * Discriminates what a {@link VerificationToken} row authorizes — domain-model.md §2: "a single
 * model serving both email verification and password reset." Kept as one table/model rather than
 * two, since both share the exact same invariants (BR-ID-04/BR-ID-05: single-use, time-limited,
 * delivered only to the email of record) and only differ in what consuming the token does.
 */
@SuppressWarnings("PMD.LongVariable") // EMAIL_VERIFICATION/PASSWORD_RESET name exactly what they
// discriminate — abbreviating either would make call sites (VerificationToken.issue(...,
// VerificationTokenType.EMAIL_VERIFICATION, ...)) harder to read, not easier.
public enum VerificationTokenType {
  EMAIL_VERIFICATION,
  PASSWORD_RESET,

  /** ADR-0024 §3: passwordless email sign-in via a short one-time code. */
  EMAIL_SIGN_IN_CODE,

  /** ADR-0024 §3: passwordless email sign-in via a single-use confirmation link. */
  EMAIL_SIGN_IN_LINK,

  /**
   * ADR-0024 §6: the step-up challenge issued to an unrecognized device when Device Trust is on.
   */
  DEVICE_TRUST_CHALLENGE
}
