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
  PASSWORD_RESET
}
