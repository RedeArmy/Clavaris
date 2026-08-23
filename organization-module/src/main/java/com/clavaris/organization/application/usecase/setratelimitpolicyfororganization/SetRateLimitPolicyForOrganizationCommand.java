package com.clavaris.organization.application.usecase.setratelimitpolicyfororganization;

import java.util.UUID;

/**
 * ADR-0010 §6.2/BR-ORG-05: v1 is operator-managed only — this command is only ever reachable via
 * the platform-tier management API (see {@code SetRateLimitPolicyController}'s own Javadoc), never
 * a tenant's own token, and never self-service.
 */
public record SetRateLimitPolicyForOrganizationCommand(
    UUID organizationId, int requestsPerMinute) {}
