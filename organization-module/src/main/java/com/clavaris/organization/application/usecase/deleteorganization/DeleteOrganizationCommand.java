package com.clavaris.organization.application.usecase.deleteorganization;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/**
 * BR-DATA-02/03's own organization-level equivalent: always a {@link AuditActor#platformClient}
 * actor — the single most destructive operation this management API exposes (an entire consuming
 * system's whole account pool, not one identity), operator/platform-only, never self-service by an
 * Organization's own owning {@code PlatformAccount}.
 */
public record DeleteOrganizationCommand(UUID organizationId, AuditActor actor) {}
