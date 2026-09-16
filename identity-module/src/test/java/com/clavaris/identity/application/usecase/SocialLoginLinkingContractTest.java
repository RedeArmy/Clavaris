package com.clavaris.identity.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;

/**
 * Code review finding (TD-ARCH-009): the tenant-tier ({@code
 * authenticatewithsocialprovider.AuthenticateWithSocialProviderService}) and platform-tier ({@code
 * authenticateplatformaccountwithsocialprovider.AuthenticatePlatformAccountWithSocialProviderService})
 * social-login linking services implement the identical three-way decision (ADR-0020 Decision 1)
 * with no shared production code — a divergence between the two has already happened twice for
 * real, both times caught late rather than by either service's own (previously entirely separate)
 * test suite: (1) the platform tier shipped without the tenant tier's own TOCTOU-race guard, caught
 * only by a later code review pass — {@link
 * #fallsBackToAPendingLinkWhenAConcurrentSignupWinsTheRaceForTheSameEmail} below now covers that;
 * (2) TD-FUT-030, a brand-new social-only signup left with zero fallback authentication method — a
 * real production risk (a provider outage was a genuine lockout, not just degraded service), found
 * and fixed on both tiers the same day but never covered by this contract class until {@link
 * #verifyANeverSurfacedPasswordCredentialWasAttachedToTheNewAccount} was added alongside it
 * (SDE-III review, 2026-09-15).
 *
 * <p>Rather than force a risky inheritance/generics refactor onto security-critical auth code right
 * now (see TD-ARCH-009's own reasoning for why that's a separately-tracked, larger initiative),
 * this is the cheaper, safer complementary fix: the exact same scenario list, run against both
 * services via this one shared abstract test class. Each tier's own concrete subclass ({@code
 * AuthenticateWithSocialProviderServiceContractTest} / {@code
 * AuthenticatePlatformAccountWithSocialProviderServiceContractTest}) implements the arrange/act/
 * assert hooks below exactly once, wiring its own tier-specific mocks; JUnit 5 discovers and runs
 * every {@code @Test} method here against each subclass independently. A future fix applied to one
 * tier and not the other now fails CI immediately — the same class of bug this test class exists to
 * prevent from recurring silently.
 *
 * @param <R> each tier's own sealed result type ({@code AuthenticateWithSocialProviderResult} /
 *     {@code AuthenticatePlatformAccountWithSocialProviderResult}) — deliberately not unified
 *     either; only the scenario shapes below are asserted to be identical, never the concrete types
 *     themselves.
 */
public abstract class SocialLoginLinkingContractTest<R> {

  protected abstract void givenNoExistingIdentity();

  protected abstract void givenAnExistingIdentityIsFound();

  protected abstract void givenNoExistingAccountForTheEmail();

  protected abstract void givenAnExistingAccountForTheEmail();

  /**
   * Arranges the brand-new-signup branch's own account-save to throw a unique-constraint violation
   * and the account lookup to then find the winning account on retry — the TOCTOU race both tiers
   * must now handle identically (code review finding, Phase 1 #1).
   */
  protected abstract void givenTheAccountSaveRacesAndLoses();

  protected abstract R invokeWithVerifiedEmail(boolean verified);

  protected abstract boolean isLoggedIn(R result);

  protected abstract boolean isConfirmationRequired(R result);

  protected abstract void verifyNoIdentityWasEverSaved();

  protected abstract void verifyAPendingLinkWasSaved();

  /**
   * SDE-III review, 2026-09-15 — closes the second real divergence this contract test class didn't
   * originally cover: TD-FUT-030 (a brand-new social-only signup left with zero authentication
   * methods other than the one linked provider, so a provider outage was a genuine lockout, not
   * just degraded service) was found and fixed on both tiers the same day — but neither service had
   * its own test suite asserting the fix at the time, and this contract test class (which by then
   * already existed, closing the earlier TOCTOU-race divergence) never grew a scenario for it
   * either. A future change to one tier's own fallback-credential logic without the matching change
   * on the other would still have passed this whole contract silently before this addition.
   */
  protected abstract void verifyANeverSurfacedPasswordCredentialWasAttachedToTheNewAccount();

  @Test
  void logsInDirectlyWhenAnIdentityIsAlreadyLinked() {
    givenAnExistingIdentityIsFound();

    R result = invokeWithVerifiedEmail(true);

    assertThat(isLoggedIn(result)).as("an already-linked identity logs in directly").isTrue();
  }

  @Test
  void createsABrandNewAccountWhenNoIdentityAndNoAccountExist() {
    givenNoExistingIdentity();
    givenNoExistingAccountForTheEmail();

    R result = invokeWithVerifiedEmail(true);

    assertThat(isLoggedIn(result))
        .as("a brand-new signup with nothing pre-existing logs in immediately")
        .isTrue();
    verifyANeverSurfacedPasswordCredentialWasAttachedToTheNewAccount();
  }

  @Test
  void raisesAPendingLinkWhenAnAccountAlreadyExistsForTheEmail() {
    givenNoExistingIdentity();
    givenAnExistingAccountForTheEmail();

    R result = invokeWithVerifiedEmail(true);

    assertThat(isConfirmationRequired(result))
        .as("an existing account reached by a different method never logs in on this request")
        .isTrue();
    verifyNoIdentityWasEverSaved();
  }

  @Test
  void fallsBackToAPendingLinkWhenAConcurrentSignupWinsTheRaceForTheSameEmail() {
    // The actual bug this contract test exists to prevent from recurring: the platform tier
    // shipped without this guard even though the tenant tier already had it (Phase 1 #1).
    givenNoExistingIdentity();
    givenNoExistingAccountForTheEmail();
    givenTheAccountSaveRacesAndLoses();

    R result = invokeWithVerifiedEmail(true);

    assertThat(isConfirmationRequired(result))
        .as(
            "the loser of a concurrent first-time-signup race falls back to the pending-link"
                + " branch instead of surfacing the race as an unhandled exception")
        .isTrue();
    verifyAPendingLinkWasSaved();
  }

  @Test
  void rejectsAnUnverifiedProviderEmail() {
    Throwable thrown = catchThrowable(() -> invokeWithVerifiedEmail(false));

    // Deliberately loose on the exact exception type: each tier throws its own dedicated type
    // (UnverifiedProviderEmailException / UnverifiedPlatformProviderEmailException) for the
    // identical reason — this contract only asserts the shared behavior (reject an unverified
    // provider email, always) holds on both, never that the two types themselves unify.
    assertThat(thrown)
        .as("both tiers must reject an unverified provider email")
        .isInstanceOf(RuntimeException.class);
  }
}
