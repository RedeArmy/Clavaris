package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Web-layer form object for the dashboard's own Sessions tuning form — same {@code
 * SetSessionPolicyRequest}-is-the-REST-API's-own-DTO split {@code SetRateLimitPolicyForm}'s own
 * Javadoc documents. The {@code @Min}/{@code @Max} bounds mirror {@code SessionPolicy}'s own range
 * constants exactly, same precedent that REST request DTO already follows.
 *
 * <p>PMD.DataClass: a plain web-layer form bean is *supposed* to be just fields + getters/setters —
 * same convention {@code CreateWorkspaceTeamRoleForm}'s own Javadoc already establishes.
 */
@SuppressWarnings({"PMD.LongVariable", "PMD.DataClass"})
public class SetSessionPolicyForm {

  @Min(5)
  @Max(5_256_000)
  private int maximumLifetimeMinutes;

  @Min(5)
  @Max(525_600)
  private int inactivityTimeoutMinutes;

  @Min(1)
  @Max(10)
  private int reverificationWindowMinutes;

  private boolean multiSessionHandlingEnabled;

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public SetSessionPolicyForm() {
    // Intentionally empty.
  }

  public int getMaximumLifetimeMinutes() {
    return maximumLifetimeMinutes;
  }

  public void setMaximumLifetimeMinutes(final int maximumLifetimeMinutes) {
    this.maximumLifetimeMinutes = maximumLifetimeMinutes;
  }

  public int getInactivityTimeoutMinutes() {
    return inactivityTimeoutMinutes;
  }

  public void setInactivityTimeoutMinutes(final int inactivityTimeoutMinutes) {
    this.inactivityTimeoutMinutes = inactivityTimeoutMinutes;
  }

  public int getReverificationWindowMinutes() {
    return reverificationWindowMinutes;
  }

  public void setReverificationWindowMinutes(final int reverificationWindowMinutes) {
    this.reverificationWindowMinutes = reverificationWindowMinutes;
  }

  public boolean isMultiSessionHandlingEnabled() {
    return multiSessionHandlingEnabled;
  }

  public void setMultiSessionHandlingEnabled(final boolean multiSessionHandlingEnabled) {
    this.multiSessionHandlingEnabled = multiSessionHandlingEnabled;
  }
}
