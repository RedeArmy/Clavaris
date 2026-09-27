package com.clavaris.organization.infrastructure.adapter.in.web;

/**
 * Web-layer form object for the dashboard's own "new workspace role" page (ADR-0027 Slice 5) — same
 * {@code CreateWorkspaceRoleRequest}-is-the-REST-API's-own-DTO split {@link CreateWorkspaceForm}'s
 * own Javadoc documents. Fields/validation live on {@link WorkspaceRoleForm} — see its own Javadoc
 * for why sharing them with {@link UpdateWorkspaceRoleForm} doesn't collapse the REST API's own
 * one-form-object-per-action convention (each still binds to a genuinely distinct {@code th:object}
 * attribute).
 */
public class CreateWorkspaceRoleForm extends WorkspaceRoleForm {

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public CreateWorkspaceRoleForm() {
    super();
    // Intentionally empty — Spring MVC/Thymeleaf just need a no-arg constructor to bind
    // form-submitted values onto via the inherited setters; there's no state to initialise here.
  }
}
