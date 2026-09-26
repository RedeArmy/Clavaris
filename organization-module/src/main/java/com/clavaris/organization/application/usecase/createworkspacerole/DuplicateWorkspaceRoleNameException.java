package com.clavaris.organization.application.usecase.createworkspacerole;

/**
 * {@code ux_workspace_roles_organization_id_name}'s own application-layer guard — a role name is
 * opaque to Clavaris (ADR-0027 §1) but must still be unique within its own Organization, same "one
 * namespace per tenant" posture {@code DuplicateAccessRestrictionEntryException}'s own sibling
 * already establishes for a different resource.
 */
public final class DuplicateWorkspaceRoleNameException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public DuplicateWorkspaceRoleNameException(final String name) {
    super("A WorkspaceRole named '" + name + "' already exists in this Organization");
  }
}
