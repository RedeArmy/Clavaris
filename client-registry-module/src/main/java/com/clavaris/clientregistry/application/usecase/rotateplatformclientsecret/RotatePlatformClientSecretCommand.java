package com.clavaris.clientregistry.application.usecase.rotateplatformclientsecret;

import com.clavaris.common.domain.model.AuditActor;

/**
 * TD-SEC-018: operator-only, never self-service — the calling {@code PlatformClient} rotates
 * another (or, in principle, its own) {@code PlatformClient}'s secret, resolved by the controller
 * from the request's own {@code Authentication}.
 */
public record RotatePlatformClientSecretCommand(String clientId, AuditActor actor) {}
