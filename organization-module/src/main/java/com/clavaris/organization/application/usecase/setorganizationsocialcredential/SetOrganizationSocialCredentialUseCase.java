package com.clavaris.organization.application.usecase.setorganizationsocialcredential;

@FunctionalInterface
public interface SetOrganizationSocialCredentialUseCase {

  SetOrganizationSocialCredentialResult handle(SetOrganizationSocialCredentialCommand command);
}
