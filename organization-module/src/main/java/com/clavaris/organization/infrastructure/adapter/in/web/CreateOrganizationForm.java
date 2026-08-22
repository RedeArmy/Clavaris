package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Web-layer form object for the dashboard's own "create organization" form — separate from {@link
 * CreateOrganizationRequest}, the REST API's own DTO (same "web knows about forms, not the domain"
 * split applied throughout identity-module).
 *
 * <p>{@code @Size(max = 255)}: matches the {@code organizations.name} column (security finding,
 * SDE-III review, 2026-08-22) — without it, an over-length name passed Bean Validation entirely and
 * only failed at the DB as an unhandled {@code DataIntegrityViolationException} (a raw 500).
 */
public class CreateOrganizationForm {

  @NotBlank(message = "Name is required")
  @Size(max = 255, message = "Name must be at most 255 characters")
  private String name;

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public CreateOrganizationForm() {
    // Intentionally empty.
  }

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }
}
