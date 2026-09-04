package com.clavaris.organization.application.usecase.deleteorganizationsocialcredential;

@FunctionalInterface
public interface DeleteOrganizationSocialCredentialUseCase {

  void handle(DeleteOrganizationSocialCredentialCommand command);
}
