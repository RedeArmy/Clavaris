package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotEmpty;
import java.util.ArrayList;
import java.util.List;

/**
 * Web-layer form object for the dashboard's own "create Secret Key" form — separate from {@link
 * CreateOrganizationClientRequest}, the REST API's own DTO (same "web knows about forms, not the
 * domain" split {@code CreateOrganizationForm}'s own Javadoc documents). {@code allowedScopes} is
 * rendered as one checkbox per {@code PlatformScopes} constant — the same full vocabulary the REST
 * API itself accepts, no dashboard-specific narrowing.
 */
public class CreateOrganizationClientForm {

  @NotEmpty(message = "Select at least one scope")
  private List<String> allowedScopes = new ArrayList<>();

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public CreateOrganizationClientForm() {
    // Intentionally empty.
  }

  public List<String> getAllowedScopes() {
    return allowedScopes;
  }

  public void setAllowedScopes(final List<String> allowedScopes) {
    this.allowedScopes = allowedScopes == null ? new ArrayList<>() : allowedScopes;
  }
}
