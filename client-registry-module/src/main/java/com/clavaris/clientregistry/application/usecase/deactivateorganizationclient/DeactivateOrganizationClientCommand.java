package com.clavaris.clientregistry.application.usecase.deactivateorganizationclient;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/**
 * SDE-III review, 2026-09-15 — real gap found and closed: {@code organizationId} used to be absent
 * entirely, so the tenant boundary for this mutation was enforced only by whichever web-layer
 * caller happened to look the client up in an already-organizationId-scoped list first ({@code
 * PlatformOrganizationClientController}'s own former {@code requireClientIdBelongsToOrganization}
 * workaround) — a future caller that skipped that step had nothing else stopping it. {@link
 * DeactivateOrganizationClientService} now verifies ownership itself whenever {@code
 * organizationId} is present.
 *
 * <p>{@code organizationId} is deliberately nullable, not a second, narrower command type: {@code
 * DeactivateOrganizationClientController} (the platform-tier {@code /api/v1/admin/
 * organization-clients/{clientId}/revoke} endpoint) has no Organization context of its own to
 * supply — a {@code PlatformClient} caller is trusted platform-wide by design (BR-PLATFORM-02), the
 * same reach it already has via {@code POST /api/v1/admin/organizations/{organizationId}/
 * secret-keys} itself. {@code PlatformOrganizationClientController} (the session-authenticated
 * dashboard, scoped to exactly one {@code PlatformAccount}-owned Organization) always supplies its
 * own real {@code organizationId} — that caller is exactly the one this check protects.
 */
public record DeactivateOrganizationClientCommand(
    String clientId, UUID organizationId, AuditActor actor) {}
