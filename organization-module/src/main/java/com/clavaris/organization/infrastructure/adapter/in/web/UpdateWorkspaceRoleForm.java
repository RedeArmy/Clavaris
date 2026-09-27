package com.clavaris.organization.infrastructure.adapter.in.web;

/**
 * Web-layer form object for the dashboard's own "edit workspace role" page (ADR-0027 Slice 5).
 * Fields/validation live on {@link WorkspaceRoleForm} — see its own Javadoc for why sharing them
 * with {@link CreateWorkspaceRoleForm} doesn't collapse the REST API's own one-form-object-per-
 * action convention (each still binds to a genuinely distinct {@code th:object} attribute).
 *
 * <p>Full-replace, not a merge-patch — same convention {@code UpdateWorkspaceRoleCommand}'s own
 * Javadoc documents: the rendered form always carries the role's complete current state, and
 * submitting it always sends the complete new desired state, never a partial diff.
 */
public class UpdateWorkspaceRoleForm extends WorkspaceRoleForm {

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public UpdateWorkspaceRoleForm() {
    super();
    // Intentionally empty — Spring MVC/Thymeleaf just need a no-arg constructor to bind
    // form-submitted values onto via the inherited setters; there's no state to initialise here.
  }
}
