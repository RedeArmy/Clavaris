package com.clavaris.identity.application.usecase.confirmpasswordreset;

/**
 * @param presentedRawToken the value from the emailed link's query parameter — never the hash,
 *     never persisted as-is
 * @param newRawPassword never logged, never persisted as-is — hashed by {@code PasswordHasher}
 *     before it touches {@code AccountRepository} (BR-ID-01), same as {@code
 *     RegisterAccountCommand#rawPassword}
 */
public record ConfirmPasswordResetCommand(String presentedRawToken, String newRawPassword) {

  // BR-ID-01: see RegisterAccountCommand's identical override for the full rationale — a record's
  // auto-generated toString() would otherwise print newRawPassword verbatim.
  @Override
  public String toString() {
    return "ConfirmPasswordResetCommand[presentedRawToken=[REDACTED], newRawPassword=[REDACTED]]";
  }
}
