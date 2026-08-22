package com.clavaris.organization.application.usecase.setratelimitpolicyfororganization;

/** Inbound port — operator-only in v1, ADR-0010 §6.2. */
@FunctionalInterface
public interface SetRateLimitPolicyForOrganizationUseCase {

  SetRateLimitPolicyForOrganizationResult handle(SetRateLimitPolicyForOrganizationCommand command);
}
