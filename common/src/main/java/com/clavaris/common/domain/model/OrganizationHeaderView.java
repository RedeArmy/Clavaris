package com.clavaris.common.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the persistent Organization header (name, environment, creation date, ID, environment
 * switcher) shown above every Organization tab needs — a plain view model, deliberately not the
 * {@code Organization} aggregate itself, since the pages that render it live in four modules that
 * cannot depend on organization-module. Populated per request by the app module's own {@code
 * DashboardOrganizationHeaderAdvice}; organization-module's own Workspaces controller also sets a
 * switcher-less one directly so that page renders the header without the app module in the loop.
 *
 * @param organizationId the Organization's own ID
 * @param name the Organization's display name
 * @param production {@code true} for a PRODUCTION environment, {@code false} for DEVELOPMENT
 * @param createdAt when the Organization was created
 * @param environments the entries of the environment switcher, or an empty list when this
 *     Organization has no paired environment to switch to (the header then shows a plain badge)
 */
public record OrganizationHeaderView(
    UUID organizationId,
    String name,
    boolean production,
    Instant createdAt,
    List<EnvironmentOption> environments) {

  public OrganizationHeaderView {
    environments = List.copyOf(environments);
  }

  /** A header with no environment switcher. */
  public OrganizationHeaderView(
      final UUID organizationId,
      final String name,
      final boolean production,
      final Instant createdAt) {
    this(organizationId, name, production, createdAt, List.of());
  }

  /** The environment label exactly as the badge shows it. */
  public String environmentLabel() {
    return production ? "PRODUCTION" : "DEVELOPMENT";
  }

  /** Whether the environment badge should be a dropdown. */
  public boolean hasEnvironmentSwitcher() {
    return !environments.isEmpty();
  }
}
