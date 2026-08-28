package com.clavaris.organization.application.usecase.createworkspace;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/**
 * No secrets in this command — the default {@code toString()} is safe as-is, same as {@code
 * CreateOrganizationCommand}.
 *
 * @param actor TD-SEC-007: always a {@link AuditActor#platformClient} actor — Workspace management
 *     is operator/consumer-application-only via {@code /api/v1/admin/**}, same tier as every other
 *     admin-API action, never self-service by an Organization's own owning {@code PlatformAccount}.
 */
public record CreateWorkspaceCommand(UUID organizationId, String name, AuditActor actor) {}
