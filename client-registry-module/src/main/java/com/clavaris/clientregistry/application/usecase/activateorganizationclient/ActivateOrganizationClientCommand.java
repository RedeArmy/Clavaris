package com.clavaris.clientregistry.application.usecase.activateorganizationclient;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/**
 * Turns a deactivated Secret Key back on. {@code organizationId} is required, never null: this is
 * only reachable from the session-authenticated dashboard, scoped to one PlatformAccount-owned
 * Organization, so a cross-tenant {@code clientId} must collapse into the same 404 a missing one
 * produces (same anti-enumeration rule {@code DeactivateOrganizationClientCommand} documents).
 */
public record ActivateOrganizationClientCommand(
    String clientId, UUID organizationId, AuditActor actor) {}
