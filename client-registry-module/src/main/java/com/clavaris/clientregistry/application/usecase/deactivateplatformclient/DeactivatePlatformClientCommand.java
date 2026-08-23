package com.clavaris.clientregistry.application.usecase.deactivateplatformclient;

import com.clavaris.common.domain.model.AuditActor;

/**
 * TD-SEC-018: operator-only — see {@code RotatePlatformClientSecretCommand}'s identical rationale.
 */
public record DeactivatePlatformClientCommand(String clientId, AuditActor actor) {}
