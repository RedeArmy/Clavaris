package com.clavaris.identity.application.usecase.authenticatewithpassword;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * BR-ID-22 (SDE-III review, 2026-09-15): {@link
 * PasswordVerifier#payVerificationCostRegardlessOfOutcome} is a {@code default} method, so it has
 * no dedicated adapter/implementation class of its own to test against — this proves the delegation
 * itself (always {@link #matches}, always with {@link
 * PasswordVerifier#DUMMY_PASSWORD_HASH_FOR_TIMING_PARITY}, regardless of what a real {@link
 * #matches} implementation would return) using a minimal recording lambda, the same shape {@code
 * Argon2PasswordVerifierTest}'s own {@code ALWAYS_ADMITTING}/{@code ALWAYS_REJECTING} gates use for
 * an interface with no state of its own to construct a mock for.
 */
class PasswordVerifierTest {

  @Test
  void delegatesToMatchesWithTheDummyHashAndDiscardsTheResult() {
    List<String> matchesCalls = new ArrayList<>();
    PasswordVerifier alwaysTrue =
        (rawPassword, passwordHash) -> {
          matchesCalls.add(rawPassword + "|" + passwordHash);
          return true;
        };

    // Must not throw even though the underlying matches() call returns true — the result is
    // never meant to be observed by the caller, only the cost of computing it.
    alwaysTrue.payVerificationCostRegardlessOfOutcome("whatever-was-typed-in-the-login-form");

    assertThat(matchesCalls)
        .containsExactly(
            "whatever-was-typed-in-the-login-form"
                + "|"
                + PasswordVerifier.DUMMY_PASSWORD_HASH_FOR_TIMING_PARITY);
  }

  // java:S2699 (SDE-III review, 2026-09-16): the previous version of this test asserted nothing —
  // "returns normally" was implicit in the absence of a thrown exception, invisible to a reader and
  // to the rule alike. assertThatCode(...).doesNotThrowAnyException() makes that expectation an
  // explicit, real assertion instead of a side effect nobody checks.
  @Test
  void neverThrowsWhenTheUnderlyingMatchesReturnsFalse() {
    PasswordVerifier alwaysFalse = (rawPassword, passwordHash) -> false;

    assertThatCode(() -> alwaysFalse.payVerificationCostRegardlessOfOutcome("some-password"))
        .doesNotThrowAnyException();
  }
}
