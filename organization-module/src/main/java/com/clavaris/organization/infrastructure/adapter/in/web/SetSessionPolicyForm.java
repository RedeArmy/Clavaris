package com.clavaris.organization.infrastructure.adapter.in.web;

/**
 * Web-layer form object for the dashboard's own Sessions tuning form — same {@code
 * SetSessionPolicyRequest}-is-the-REST-API's-own-DTO split {@code SetRateLimitPolicyForm}'s own
 * Javadoc documents. The REST request keeps speaking minutes; this form speaks what a person types:
 * an amount and a unit for each duration.
 *
 * <p>The amounts and units are plain text on purpose. A number field bound to an {@code int} turns
 * "abc" or "1.5" into a framework conversion error nobody can read; held as text, {@link
 * SessionPolicyFormValidator} parses them and says what is wrong in the words the page uses. It
 * also keeps exactly what was typed when the form is shown again with an error.
 *
 * <p>PMD.DataClass: a plain web-layer form bean is *supposed* to be just fields + getters/setters —
 * same convention {@code CreateWorkspaceTeamRoleForm}'s own Javadoc already establishes.
 */
@SuppressWarnings({"PMD.LongVariable", "PMD.DataClass"})
public class SetSessionPolicyForm {

  private String maximumLifetimeValue;
  private String maximumLifetimeUnit;
  private String inactivityTimeoutValue;
  private String inactivityTimeoutUnit;
  private String reverificationWindowMinutes;
  private boolean multiSessionHandlingEnabled;

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public SetSessionPolicyForm() {
    // Intentionally empty.
  }

  public String getMaximumLifetimeValue() {
    return maximumLifetimeValue;
  }

  public void setMaximumLifetimeValue(final String maximumLifetimeValue) {
    this.maximumLifetimeValue = maximumLifetimeValue;
  }

  public String getMaximumLifetimeUnit() {
    return maximumLifetimeUnit;
  }

  public void setMaximumLifetimeUnit(final String maximumLifetimeUnit) {
    this.maximumLifetimeUnit = maximumLifetimeUnit;
  }

  public String getInactivityTimeoutValue() {
    return inactivityTimeoutValue;
  }

  public void setInactivityTimeoutValue(final String inactivityTimeoutValue) {
    this.inactivityTimeoutValue = inactivityTimeoutValue;
  }

  public String getInactivityTimeoutUnit() {
    return inactivityTimeoutUnit;
  }

  public void setInactivityTimeoutUnit(final String inactivityTimeoutUnit) {
    this.inactivityTimeoutUnit = inactivityTimeoutUnit;
  }

  public String getReverificationWindowMinutes() {
    return reverificationWindowMinutes;
  }

  public void setReverificationWindowMinutes(final String reverificationWindowMinutes) {
    this.reverificationWindowMinutes = reverificationWindowMinutes;
  }

  public boolean isMultiSessionHandlingEnabled() {
    return multiSessionHandlingEnabled;
  }

  public void setMultiSessionHandlingEnabled(final boolean multiSessionHandlingEnabled) {
    this.multiSessionHandlingEnabled = multiSessionHandlingEnabled;
  }
}
