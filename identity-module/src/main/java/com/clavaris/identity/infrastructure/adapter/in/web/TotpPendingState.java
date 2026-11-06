package com.clavaris.identity.infrastructure.adapter.in.web;

/**
 * ADR-0040: the {@code HttpSession} attributes a sign-in paused for the authenticator code is
 * carried on, same plain-String shape and same reasons as {@link DeviceTrustPendingState}. The
 * pause is the last one before the session is established, so resuming it is the common {@link
 * AuthenticatedSessionCompletion#completeFromPendingChallenge}.
 */
@SuppressWarnings("PMD.LongVariable")
/* package */ final class TotpPendingState {

  /* package */ static final String ACCOUNT_ID_ATTRIBUTE = "clavaris.totp.pendingAccountId";
  /* package */ static final String FACTOR_ATTRIBUTE = "clavaris.totp.pendingFactor";
  /* package */ static final String ORGANIZATION_ID_ATTRIBUTE =
      "clavaris.totp.pendingOrganizationId";
  /* package */ static final String CLIENT_ID_ATTRIBUTE = "clavaris.totp.pendingClientId";
  /* package */ static final String REDIRECT_URL_ATTRIBUTE = "clavaris.totp.pendingRedirectUrl";
  /* package */ static final String PROVIDER_ATTRIBUTE = "clavaris.totp.pendingProvider";
  // TD-SEC-093: names this one pause, so the entries it allows are counted for it and a fresh
  // password check starts a fresh budget.
  /* package */ static final String CHALLENGE_ID_ATTRIBUTE = "clavaris.totp.pendingChallengeId";
  // "true" when a device-trust challenge was already passed on the way here, so the resumed
  // sign-in still claims it.
  /* package */ static final String DEVICE_TRUST_VERIFIED_ATTRIBUTE =
      "clavaris.totp.pendingDeviceTrustVerified";

  private TotpPendingState() {
    // Constants only.
  }
}
