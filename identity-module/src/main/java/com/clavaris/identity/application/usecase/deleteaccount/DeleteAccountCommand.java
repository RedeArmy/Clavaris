package com.clavaris.identity.application.usecase.deleteaccount;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.AccountId;

/**
 * BR-DATA-02: originally always a {@link AuditActor#platformClient} actor from the {@code
 * /api/v1/admin/**} management-API surface (`AdminApiSecurityConfig`) — never reachable with a
 * tenant `Account`'s own token, so there is no self-service variant of this command the way {@code
 * CreateOrganizationCommand} has one. SDE-III review, 2026-09-19: a second, equally privileged
 * caller now exists — {@code PlatformAccountLifecycleController}'s dashboard "Delete user" menu
 * item, which constructs this with an {@link AuditActor#platformAccount} actor (a
 * session-authenticated operator) instead — same platform trust tier, different transport.
 */
public record DeleteAccountCommand(AccountId accountId, AuditActor actor) {}
