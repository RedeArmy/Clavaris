package com.clavaris.clientregistry.application.usecase.rotateoauthclientsecret;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/**
 * SDE-III review, 2026-09-15: same required-{@code organizationId} shape and same rationale as
 * {@code deactivateoauthclient.DeactivateOAuthClientCommand} — see that class's own Javadoc.
 */
public record RotateOAuthClientSecretCommand(
    String clientId, UUID organizationId, AuditActor actor) {}
