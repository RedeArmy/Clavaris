package com.clavaris.organization.application.usecase.listorganizationsocialcredentials;

import java.util.List;
import java.util.UUID;

@FunctionalInterface
public interface ListOrganizationSocialCredentialsUseCase {

  List<ListedOrganizationSocialCredential> handle(UUID organizationId);
}
