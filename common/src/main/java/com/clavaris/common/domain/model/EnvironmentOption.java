package com.clavaris.common.domain.model;

/**
 * One entry of the environment switcher in the Organization header (Clerk's Development /
 * Production dropdown). Each environment is its own {@code Organization} with its own account pool,
 * keys and OAuth clients (ADR-0010) — choosing an option is plain navigation to that other
 * Organization's page, never a data operation.
 *
 * @param production {@code true} for the PRODUCTION entry, {@code false} for DEVELOPMENT
 * @param current whether this is the environment the page is currently showing
 * @param setUp {@code false} when the environment does not exist yet (a development Organization
 *     that has not been promoted); {@code href} then points at the "Promote to Production" page
 * @param href app-relative target of the entry (never includes the context path)
 * @param organizationName the Organization behind the entry, or {@code null} when {@code setUp} is
 *     {@code false}
 */
public record EnvironmentOption(
    boolean production, boolean current, boolean setUp, String href, String organizationName) {

  /** The environment label exactly as the badge and the menu show it. */
  public String label() {
    return production ? "Production" : "Development";
  }
}
