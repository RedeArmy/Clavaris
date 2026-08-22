package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;

/**
 * Web-layer form object for the dashboard's own "create organization" form — separate from {@link
 * CreateOrganizationRequest}, the REST API's own DTO (same "web knows about forms, not the domain"
 * split applied throughout identity-module).
 */
public class CreateOrganizationForm {

  @NotBlank(message = "Name is required")
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
