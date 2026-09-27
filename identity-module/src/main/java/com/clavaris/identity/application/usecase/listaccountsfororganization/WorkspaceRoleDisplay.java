package com.clavaris.identity.application.usecase.listaccountsfororganization;

import java.util.UUID;

/**
 * ADR-0029: everything the Users-tab's own "Role" column and "Assign role" link need about an
 * Account's single Workspace membership — {@code roleId} is {@code null} when the Account belongs
 * to a Workspace but currently has no role assigned (ADR-0027 §5's own explicitly allowed state).
 */
public record WorkspaceRoleDisplay(UUID workspaceId, UUID roleId, String roleName) {}
