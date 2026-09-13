package com.clavaris.organization.application.usecase.getratelimitpolicyfororganization;

import java.util.UUID;

@FunctionalInterface
public interface GetRateLimitPolicyForOrganizationUseCase {

  RateLimitPolicySnapshot handle(UUID organizationId);
}
