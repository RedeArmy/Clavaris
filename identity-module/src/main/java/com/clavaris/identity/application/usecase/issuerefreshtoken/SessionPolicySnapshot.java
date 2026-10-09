package com.clavaris.identity.application.usecase.issuerefreshtoken;

/**
 * identity-module's own read view of an Organization's session policy — mirrors
 * organization-module's {@code SessionPolicy} field-for-field, deliberately a separate type (module
 * independence: neither business module may depend on the other's domain type), same "mirror, never
 * share" rule {@code AccountAuthenticationPolicySnapshot} already establishes for an identical
 * need.
 *
 * <p>{@link #defaults()} matches organization-module's own {@code SessionPolicy#defaults(...)}
 * values exactly (kept in sync by hand, no single source of truth spans both modules) — used by
 * {@code SessionPolicyProviderBridge} (app) only as a defensive fallback; the real "no row yet"
 * default is already resolved by organization-module's own {@code
 * GetSessionPolicyForOrganizationUseCase} before this snapshot is ever built.
 */
public record SessionPolicySnapshot(
    int maximumLifetimeMinutes,
    int inactivityTimeoutMinutes,
    int reverificationWindowMinutes,
    boolean multiSessionHandlingEnabled) {

  public static SessionPolicySnapshot defaults() {
    return new SessionPolicySnapshot(10_080, 10_080, 10, true);
  }
}
