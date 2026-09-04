package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.application.usecase.setorganizationsocialcredential.SetOrganizationSocialCredentialResult;
import com.clavaris.organization.domain.model.SocialProvider;
import java.time.Instant;

/**
 * Deliberately never carries {@code clientSecret} — see {@code
 * SetOrganizationSocialCredentialResult}'s own Javadoc for why.
 */
public record SetOrganizationSocialCredentialResponse(
    SocialProvider provider, String clientId, Instant updatedAt) {

  public static SetOrganizationSocialCredentialResponse from(
      final SetOrganizationSocialCredentialResult result) {
    return new SetOrganizationSocialCredentialResponse(
        result.credential().provider(),
        result.credential().clientId(),
        result.credential().updatedAt());
  }
}
