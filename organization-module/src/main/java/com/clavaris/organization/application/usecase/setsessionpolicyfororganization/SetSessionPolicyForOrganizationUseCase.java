package com.clavaris.organization.application.usecase.setsessionpolicyfororganization;

/** Inbound port. */
@FunctionalInterface
public interface SetSessionPolicyForOrganizationUseCase {

  SetSessionPolicyForOrganizationResult handle(SetSessionPolicyForOrganizationCommand command);
}
