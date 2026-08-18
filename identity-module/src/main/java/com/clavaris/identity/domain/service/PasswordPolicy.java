package com.clavaris.identity.domain.service;

/**
 * Pure domain rule — no framework dependency, no I/O. Length bounds only: no dedicated business
 * rule yet specifies a fuller policy, and both a mandatory character-class mix and a
 * breached-password corpus check (BR-ID-07, explicitly deferred to v1.1, `prd-mvp.md` §2.1) are
 * deliberately NOT here — current OWASP/NIST SP 800-63B guidance actively discourages composition
 * rules (they push users toward predictable patterns, e.g. "Password1!") in favour of length plus a
 * breach check; adding one now would be regressive, not protective, ahead of BR-ID-07 landing.
 *
 * <ul>
 *   <li>{@link #MIN_LENGTH} (8) — the OWASP-recommended floor for a hashed, rate-limited
 *       credential.
 *   <li>{@link #MAX_LENGTH} (128) — NOT a security-through-obscurity rule; it's a denial-of-service
 *       defence against the hashing step itself. Argon2id's cost is proportional to input size
 *       (unlike bcrypt's hard 72-byte truncation), so an attacker submitting a multi-megabyte
 *       "password" repeatedly against the registration endpoint would burn disproportionate CPU per
 *       request before rate limiting even engages. 128 comfortably fits any real passphrase (NIST
 *       SP 800-63B only requires supporting *at least* 64) while bounding the hashing cost to a
 *       known constant.
 * </ul>
 */
public final class PasswordPolicy {

  private static final int MIN_LENGTH = 8;
  private static final int MAX_LENGTH = 128;

  private PasswordPolicy() {}

  /**
   * @return true if {@code rawPassword} satisfies the policy.
   */
  public static boolean isSatisfiedBy(final String rawPassword) {
    return rawPassword != null
        && rawPassword.length() >= MIN_LENGTH
        && rawPassword.length() <= MAX_LENGTH;
  }
}
