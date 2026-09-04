package com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization;

@FunctionalInterface
public interface SetAccountAuthenticationPolicyForOrganizationUseCase {

  SetAccountAuthenticationPolicyForOrganizationResult handle(
      SetAccountAuthenticationPolicyForOrganizationCommand command);
}
