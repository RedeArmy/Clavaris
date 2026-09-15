package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotEmpty;
import java.util.ArrayList;
import java.util.List;

/**
 * Web-layer form object for the dashboard's own "create Secret Key" form — separate from {@link
 * CreateOrganizationClientRequest}, the REST API's own DTO (same "web knows about forms, not the
 * domain" split {@code CreateOrganizationForm}'s own Javadoc documents). {@code allowedScopes} is
 * rendered as one checkbox per {@code PlatformScopes.ORGANIZATION_CLIENT_ALLOWED} constant (SDE-III
 * review, 2026-09-15 — was the full {@code BOOTSTRAP_DEFAULT} vocabulary, including scopes {@code
 * OrganizationClient#register} can never actually grant; see {@code PlatformScopes.OPERATOR_ONLY}'s
 * own Javadoc) — {@link PlatformOrganizationClientController} still submits whatever the client
 * posts, so {@code OrganizationClient.register}'s own domain-level validation remains the real,
 * unconditional guarantee, not this form's rendering choice.
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
