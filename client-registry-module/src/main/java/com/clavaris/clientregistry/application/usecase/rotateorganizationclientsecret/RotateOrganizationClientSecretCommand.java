package com.clavaris.clientregistry.application.usecase.rotateorganizationclientsecret;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/**
 * SDE-III review, 2026-09-15: same nullable-{@code organizationId} shape and same rationale as
 * {@code deactivateorganizationclient.DeactivateOrganizationClientCommand} — see that class's own
 * Javadoc.
 */
public record RotateOrganizationClientSecretCommand(
    String clientId, UUID organizationId, AuditActor actor) {}
