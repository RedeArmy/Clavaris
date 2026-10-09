package com.clavaris.organization.application.usecase.setsessionpolicyfororganization;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/**
 * Reachable via the platform-tier management API ({@code SetSessionPolicyController}, {@code
 * PlatformScopes.SESSION_POLICY_WRITE}) and the session-authenticated dashboard ({@code
 * PlatformSessionPolicyController}) — same dual-caller, single-use-case shape {@code
 * SetRateLimitPolicyForOrganizationCommand}'s own Javadoc documents.
 *
 * @param actor either the calling {@code PlatformClient} (the REST admin API path) or a {@link
 *     AuditActor#platformAccount} actor (the dashboard's own session-authenticated caller, tuning
 *     its own tenant's session policy). The dashboard's own ownership check happens before this
 *     command is ever built, not inside this use case — same split the rate-limit policy's own
 *     command already documents.
 */
@SuppressWarnings("PMD.LongVariable")
public record SetSessionPolicyForOrganizationCommand(
    UUID organizationId,
    int maximumLifetimeMinutes,
    int inactivityTimeoutMinutes,
    int reverificationWindowMinutes,
    boolean multiSessionHandlingEnabled,
    AuditActor actor) {}
