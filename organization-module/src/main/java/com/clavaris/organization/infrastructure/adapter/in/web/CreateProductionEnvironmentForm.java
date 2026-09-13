package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Web-layer form object for the dashboard's own "promote to production" form — separate from the
 * REST API's own {@code CreateProductionEnvironmentRequest}, same "web knows about forms, not the
 * domain" split every other form object in this codebase already establishes.
 */
public class CreateProductionEnvironmentForm {

  @NotBlank(message = "Enter a name for the production environment")
  @Size(max = 255, message = "Name must not exceed 255 characters")
  private String name = "";

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public CreateProductionEnvironmentForm() {
    // Intentionally empty.
  }

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name == null ? "" : name;
  }
}
