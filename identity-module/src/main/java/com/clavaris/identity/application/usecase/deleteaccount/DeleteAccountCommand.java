package com.clavaris.identity.application.usecase.deleteaccount;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.AccountId;

/**
 * BR-DATA-02: always a {@link AuditActor#platformClient} actor — this is the {@code
 * /api/v1/admin/**} management-API surface (`AdminApiSecurityConfig`), never reachable with a
 * tenant `Account`'s or a `PlatformAccount`'s own token, so there is no self-service variant of
 * this command the way {@code CreateOrganizationCommand} has one.
 */
public record DeleteAccountCommand(AccountId accountId, AuditActor actor) {}
