package com.clavaris.identity.application.usecase.authenticatewithpassword;

/**
 * TD-FUT-017: thrown by {@link PasswordVerifier} when the shared Argon2id concurrency gate
 * (`common`'s {@code CpuBoundVerificationGate}) is saturated and no permit freed up in time — the
 * password check never actually ran, so this is never a wrong-credential outcome and must never be
 * rendered or handled as one (that would both mislead the caller and, worse, could feed a
 * legitimate burst into BR-ID-06's own failed-attempt lockout counters for credentials that were
 * never actually checked). Same package as {@link InvalidCredentialsException}/{@link
 * EmailNotVerifiedException} — reused by {@code authenticatewithusername} too, same precedent those
 * two already establish.
 */
public final class VerificationOverloadedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public VerificationOverloadedException() {
    super("Password verification temporarily unavailable — too many concurrent requests");
  }
}
