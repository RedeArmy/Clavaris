package com.clavaris.identity.application.usecase.rotatesigningkeyfororganization;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.OrganizationId;

/**
 * ADR-0010 §5.2: v1 rotation is manually-triggered and operator-only, never self-service — this
 * command is only ever reachable via the platform-tier management API, same posture as {@code
 * SetRateLimitPolicyForOrganizationCommand}.
 *
 * @param actor TD-SEC-007: the calling {@code PlatformClient}, resolved by the controller from the
 *     request's own {@code Authentication}.
 */
public record RotateSigningKeyForOrganizationCommand(
    OrganizationId organizationId, AuditActor actor) {}
