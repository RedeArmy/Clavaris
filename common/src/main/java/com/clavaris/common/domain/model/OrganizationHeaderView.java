package com.clavaris.common.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * What the persistent Organization header (name, environment, creation date, ID) shown above every
 * Organization tab needs — a plain view model, deliberately not the {@code Organization} aggregate
 * itself, since the pages that render it live in four modules that cannot depend on
 * organization-module. Populated per request by the app module's own {@code
 * DashboardOrganizationHeaderAdvice}; organization-module's own Workspaces controller also sets it
 * directly so that page renders the same header without the app module in the loop.
 *
 * @param organizationId the Organization's own ID
 * @param name the Organization's display name
 * @param production {@code true} for a PRODUCTION environment, {@code false} for DEVELOPMENT
 * @param createdAt when the Organization was created
 */
public record OrganizationHeaderView(
    UUID organizationId, String name, boolean production, Instant createdAt) {

  /** The environment label exactly as the badge shows it. */
  public String environmentLabel() {
    return production ? "PRODUCTION" : "DEVELOPMENT";
  }
}
