package com.clavaris.organization.application.usecase.updateworkspacerole;

import com.clavaris.common.domain.model.AuditActor;
import java.util.Set;
import java.util.UUID;

/**
 * A full-replace command, not a JSON-merge-patch — {@code name}/{@code parentRoleId}/{@code
 * permissions} are the role's complete new desired state, even though the HTTP verb is {@code
 * PATCH}. Same "resend the full value, not a delta" convention {@code
 * ChangeWorkspaceMemberRoleRequest#roleId} already establishes for an identical reason: a nullable
 * field's own absence-vs-null ambiguity in a true partial patch isn't worth resolving for a v1
 * management-API resource this small.
 *
 * @param organizationId TD-SEC-056 (fixed): same anti-enumeration check {@code
 *     DeleteWorkspaceRoleCommand}'s own identical field documents in full — previously absent, so
 *     {@code WorkspaceRolesController}'s {@code organizationId} path variable was purely decorative
 *     for this verb too.
 * @param parentRoleId nullable — {@code null} clears the parent (ADR-0027 §3).
 */
public record UpdateWorkspaceRoleCommand(
    UUID roleId,
    UUID organizationId,
    String name,
    UUID parentRoleId,
    Set<String> permissions,
    AuditActor actor) {}
