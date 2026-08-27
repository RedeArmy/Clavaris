package com.clavaris.organization.application.usecase.removeworkspacemember;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/**
 * BR-WS-03: removes only the {@code WorkspaceMembership} row — never the {@code Account} itself,
 * which stays fully intact (a separate concern, {@code DeleteAccountService}'s own, identity-module
 * -owned scope).
 */
public record RemoveWorkspaceMemberCommand(UUID workspaceId, UUID accountId, AuditActor actor) {}
