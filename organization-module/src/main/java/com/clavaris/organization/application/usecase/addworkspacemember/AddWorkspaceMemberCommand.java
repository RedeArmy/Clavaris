package com.clavaris.organization.application.usecase.addworkspacemember;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.UUID;

/**
 * No secret in this command — unlike {@code RegisterAccountCommand}, the new member's password is
 * never known to the caller (BR-WS-04: generated internally by {@link AccountProvisioner}'s own
 * implementation, never surfaced), so the default {@code toString()} is safe as-is.
 *
 * @param role defaults to {@link WorkspaceRole#MEMBER} at the web layer when omitted — BR-WS-05:
 *     the only two values this system knows about.
 */
public record AddWorkspaceMemberCommand(
    UUID workspaceId, String email, WorkspaceRole role, AuditActor actor) {}
