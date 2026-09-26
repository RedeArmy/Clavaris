package com.clavaris.organization.application.usecase.createworkspacerole;

import com.clavaris.common.domain.model.AuditActor;
import java.util.Set;
import java.util.UUID;

/**
 * @param parentRoleId nullable — no parent means no inherited permissions (ADR-0027 §3).
 * @param permissions opaque, consumer-defined strings (ADR-0027 §1) — never validated against a
 *     catalog, same posture {@code AddWorkspaceMemberCommand}'s sibling commands already hold for
 *     an identical reason.
 */
public record CreateWorkspaceRoleCommand(
    UUID organizationId,
    String name,
    UUID parentRoleId,
    Set<String> permissions,
    AuditActor actor) {}
