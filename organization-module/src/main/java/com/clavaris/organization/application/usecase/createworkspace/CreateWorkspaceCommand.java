package com.clavaris.organization.application.usecase.createworkspace;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/**
 * No secrets in this command — the default {@code toString()} is safe as-is, same as {@code
 * CreateOrganizationCommand}.
 *
 * @param actor TD-SEC-007: either a {@link AuditActor#platformClient} actor, calling {@code
 *     /api/v1/admin/**} on behalf of a consuming application, or (ADR-0025, since the dashboard's
 *     own {@code PlatformWorkspaceController} started calling this same use case) a {@link
 *     AuditActor#platformAccount} actor — genuine self-service by the Organization's own owning
 *     {@code PlatformAccount}, same posture {@code CreateOrganizationCommand}'s own actor already
 *     established for Organization creation. Never a PlatformAccount acting on an Organization it
 *     doesn't own — the dashboard's own ownership check happens before this command is ever built,
 *     not inside this use case.
 */
public record CreateWorkspaceCommand(UUID organizationId, String name, AuditActor actor) {}
