package com.clavaris.identity.infrastructure.adapter.in.web;

/**
 * ADR-0024 §6: the {@code HttpSession} attribute keys a paused-for-device-trust login is carried
 * across on — {@link DeviceTrustGate} is the sole writer (right before redirecting to the
 * challenge), {@link DeviceTrustChallengeController} is the sole reader. One shared constants
 * holder, not a literal string duplicated in both places — same "tiny, private-constructor
 * constants class" shape as {@code SessionDeviceAttributes} ({@code app} module).
 *
 * <p>Plain {@code String} values only — same {@code JdkSerializationRedisSerializer} constraint
 * {@code SessionDeviceAttributes}'s own Javadoc documents; {@code accountId} is the UUID's string
 * form, {@code factor} is a {@link PendingAuthenticationFactor} enum name, {@code organizationId}
 * is stored alongside them purely as a defense-in-depth BR-ORG-02 cross-tenant check — the
 * challenge controller's own path already names the Organization, but a session that started a
 * challenge under one Organization's login must never be resumable from another's URL.
 */
// PMD.LongVariable: descriptive session-attribute-key constant names, deliberately long — same
// "deliberate record-style naming" precedent as KnownDevice's own class-level suppression.
@SuppressWarnings("PMD.LongVariable")
final class DeviceTrustPendingState {

  /* package */ static final String ACCOUNT_ID_ATTRIBUTE = "clavaris.deviceTrust.pendingAccountId";

  /* package */ static final String FACTOR_ATTRIBUTE = "clavaris.deviceTrust.pendingFactor";

  /* package */ static final String ORGANIZATION_ID_ATTRIBUTE =
      "clavaris.deviceTrust.pendingOrganizationId";

  private DeviceTrustPendingState() {
    // Constants only.
  }
}
