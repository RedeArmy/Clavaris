package com.clavaris.identity.application.usecase.authenticatewithpassword;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/security/Argon2PasswordVerifier}. Deliberately a separate port from
 * {@code registeraccount.PasswordHasher} rather than a second method bolted onto it: this
 * codebase's established convention is narrow, single-purpose ports named for exactly what they do
 * (see also {@code OrganizationExistsChecker}, {@code SigningKeyProvisioner}) — "hash" and "verify"
 * are different operations even though ADR-0005's Argon2 encoder happens to expose both.
 */
@FunctionalInterface
public interface PasswordVerifier {

  /**
   * BR-ID-22 (SDE-III review, 2026-09-15): a real, valid-format Argon2id hash (same cost parameters
   * {@code Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()} — ADR-0005 — produces for every
   * real credential in this system) of a fixed password no account will ever actually have. Never a
   * real credential, never compared against anything meaningful.
   */
  @SuppressWarnings("PMD.LongVariable") // names exactly what it holds — same precedent
  // MAX_ENDPOINTS_PER_ORGANIZATION's own identical suppression documents (webhook-module,
  // RegisterWebhookEndpointService) for an equally self-explanatory constant name.
  String DUMMY_PASSWORD_HASH_FOR_TIMING_PARITY =
      "$argon2id$v=19$m=16384,t=2,p=1$dpXFtza1qHtma0EoXDpqcg$IWmvSHJVU73oWp/OB2crvi/ZyUqSREwIKg6YN8Tl9nY";

  boolean matches(String rawPassword, String passwordHash);

  /**
   * BR-ID-22: every {@code AuthenticateWith*Service}'s own uniform-{@code
   * InvalidCredentialsException} response is only genuinely indistinguishable across rejection
   * reasons if every rejection branch pays the identical Argon2id cost — an unknown account, an
   * inactive account, or an account with no password credential must never return in the
   * microseconds a bare lookup takes while a wrong-password rejection takes the tens of
   * milliseconds a real {@link #matches} call costs by design. Callers with no real {@code
   * passwordHash} to check against call this instead, right before logging/throwing, and discard
   * the result — it can only ever be {@code false} ({@link #DUMMY_PASSWORD_HASH_FOR_TIMING_PARITY}
   * matches no real password). A {@code default} method, not a per-caller copy of the same one-line
   * body: three call sites (tenant email login, tenant username login, platform login) would
   * otherwise duplicate both the dummy hash and this method verbatim.
   *
   * <p>Still goes through whatever concurrency gate a real implementation wraps {@link #matches} in
   * (see {@code Argon2PasswordVerifier}'s own Javadoc) — deliberately: a saturated gate must reject
   * this path exactly as loudly as it would reject a real, account-exists attempt, or gate
   * saturation itself would become a second, cruder timing/availability side channel.
   */
  default void payVerificationCostRegardlessOfOutcome(final String rawPassword) {
    matches(rawPassword, DUMMY_PASSWORD_HASH_FOR_TIMING_PARITY);
  }
}
