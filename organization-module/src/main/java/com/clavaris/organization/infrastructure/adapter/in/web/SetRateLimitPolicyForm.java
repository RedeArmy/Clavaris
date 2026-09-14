package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.validation.constraints.Positive;

/**
 * Web-layer form object for the dashboard's own Rate Limit tuning form on the Organization-detail
 * page (TD-FUT-002, self-service tuning) — same {@code SetRateLimitPolicyRequest}-is-the-REST-
 * API's-own-DTO split {@link CreateOrganizationForm}'s own Javadoc documents. {@code @Positive}
 * only rules out a non-positive value here; the hard-system-wide-cap bound is enforced once, in
 * {@code RateLimitPolicy}'s own factory/update methods, not duplicated as a Bean Validation
 * constraint that would need the cap value threaded into this form just to express it.
 */
public class SetRateLimitPolicyForm {

  @Positive(message = "Requests per minute must be a positive number")
  private int requestsPerMinute;

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public SetRateLimitPolicyForm() {
    // Intentionally empty.
  }

  public int getRequestsPerMinute() {
    return requestsPerMinute;
  }

  public void setRequestsPerMinute(final int requestsPerMinute) {
    this.requestsPerMinute = requestsPerMinute;
  }
}
