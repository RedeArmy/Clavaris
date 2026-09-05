package com.clavaris.identity.domain.service;

import java.security.SecureRandom;

/**
 * ADR-0024 §5: when {@code passwordAtSignUpEnabled=false}, {@code RegisterAccountService} still
 * needs a real {@code PasswordCredential} row attached — BR-ID-02 ("never zero auth methods") is
 * enforced at the database level (migration {@code V20260830110000}'s own deferred constraint
 * trigger), which only ever recognizes {@code password_credentials}/{@code social_identities} rows,
 * not a transient {@code VerificationToken}. Same established precedent {@code
 * WorkspaceMemberAccountProvisionerBridge}'s own {@code generateRandomPassword()} already sets for
 * an identical need (a workspace-provisioned member's very first password, before they set their
 * own via the reset-link email) — a real, cryptographically random value that satisfies {@link
 * PasswordPolicy}, is never returned to any caller, logged, or otherwise retained, and is never the
 * account's actual sign-in method (that's the passwordless email flow instead, ADR-0024 §3).
 *
 * <p>Not shared code with that app-module class (the module dependency direction runs the wrong way
 * — {@code app} depends on identity-module, never the reverse) — a small, deliberate duplication of
 * the same idea, same "generate a strong, never-surfaced placeholder" shape, kept local to each
 * module that needs it.
 */
@SuppressWarnings("PMD.LongVariable")
public final class RandomPasswordGenerator {

  // Long enough that brute-forcing this never-returned, never-logged, immediately-superseded
  // value is not a meaningful attack surface; well within PasswordPolicy's own 128-char ceiling.
  private static final int GENERATED_PASSWORD_LENGTH = 32;
  private static final String PASSWORD_ALPHABET =
      "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%^&*";
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private RandomPasswordGenerator() {
    // Static utility — not instantiable, same convention as RefreshTokenSecret.
  }

  public static String generate() {
    final StringBuilder generated = new StringBuilder(GENERATED_PASSWORD_LENGTH);
    for (int i = 0; i < GENERATED_PASSWORD_LENGTH; i++) {
      generated.append(PASSWORD_ALPHABET.charAt(SECURE_RANDOM.nextInt(PASSWORD_ALPHABET.length())));
    }
    return generated.toString();
  }
}
