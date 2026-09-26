package com.clavaris.organization.application.usecase.addworkspacemember;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/**
 * No secret in this command — unlike {@code RegisterAccountCommand}, the new member's password is
 * never known to the caller (BR-WS-04: generated internally by {@link AccountProvisioner}'s own
 * implementation, never surfaced), so the default {@code toString()} is safe as-is.
 *
 * @param roleId ADR-0027 — a {@code WorkspaceRole} id, resolved and validated by {@link
 *     AddWorkspaceMemberService} against the Workspace's own Organization; must reference an
 *     existing role (unlike {@code ChangeWorkspaceMemberRoleCommand#newRoleId}, adding a member
 *     with no role at all isn't a supported entry point — see that command's own Javadoc).
 */
public record AddWorkspaceMemberCommand(
    UUID workspaceId, String email, UUID roleId, AuditActor actor) {}
