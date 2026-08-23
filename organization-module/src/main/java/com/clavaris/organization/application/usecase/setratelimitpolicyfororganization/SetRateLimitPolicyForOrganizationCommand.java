package com.clavaris.organization.application.usecase.setratelimitpolicyfororganization;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/**
 * ADR-0010 §6.2/BR-ORG-05: v1 is operator-managed only — this command is only ever reachable via
 * the platform-tier management API (see {@code SetRateLimitPolicyController}'s own Javadoc), never
 * a tenant's own token, and never self-service.
 *
 * @param actor TD-SEC-007: the calling {@code PlatformClient}, resolved by the controller from the
 *     request's own {@code Authentication} — this endpoint has no self-service path, so unlike
 *     {@code CreateOrganizationCommand} there is only ever one kind of actor here.
 */
public record SetRateLimitPolicyForOrganizationCommand(
    UUID organizationId, int requestsPerMinute, AuditActor actor) {}
