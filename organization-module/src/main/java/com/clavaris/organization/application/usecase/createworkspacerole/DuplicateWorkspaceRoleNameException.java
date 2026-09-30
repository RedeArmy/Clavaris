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

  /**
   * TD-SEC-060: same message, plus the low-level exception that revealed the conflict (a lost race
   * against {@code ux_workspace_roles_organization_id_name}, {@link
   * com.clavaris.organization.application.usecase.createworkspacerole.CreateWorkspaceRoleService})
   * — preserves its stack trace instead of discarding it, same precedent {@code
   * EmailAlreadyRegisteredException}'s own two-constructor shape already establishes.
   */
  public DuplicateWorkspaceRoleNameException(final String name, final Throwable cause) {
    super("A WorkspaceRole named '" + name + "' already exists in this Organization", cause);
  }
}
