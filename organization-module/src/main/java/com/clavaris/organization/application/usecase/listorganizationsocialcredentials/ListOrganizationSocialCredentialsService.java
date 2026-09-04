package com.clavaris.organization.application.usecase.listorganizationsocialcredentials;

import com.clavaris.organization.application.usecase.setorganizationsocialcredential.OrganizationSocialCredentialRepository;
import com.clavaris.organization.domain.model.OrganizationSocialCredential;
import java.util.List;
import java.util.UUID;

/**
 * Read-only — no existence check on {@code organizationId} needed: an empty list is a valid, safe
 * answer either way (unknown Organization or a real one with nothing configured), same "absence is
 * normal, not exceptional" posture {@link OrganizationSocialCredentialRepository}'s own Javadoc
 * already establishes.
 */
public class ListOrganizationSocialCredentialsService
    implements ListOrganizationSocialCredentialsUseCase {

  private final OrganizationSocialCredentialRepository credentials;

  public ListOrganizationSocialCredentialsService(
      final OrganizationSocialCredentialRepository credentials) {
    this.credentials = credentials;
  }

  @Override
  public List<ListedOrganizationSocialCredential> handle(final UUID organizationId) {
    return credentials.findAllByOrganizationId(organizationId).stream()
        .map(ListOrganizationSocialCredentialsService::toListed)
        .toList();
  }

  private static ListedOrganizationSocialCredential toListed(
      final OrganizationSocialCredential credential) {
    return new ListedOrganizationSocialCredential(
        credential.provider(), credential.clientId(), credential.updatedAt());
  }
}
