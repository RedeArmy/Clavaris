package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.domain.model.Organization;
import java.util.List;
import java.util.UUID;

public record SetSocialLoginPolicyResponse(
    UUID organizationId, boolean socialLoginEnabled, List<String> allowedSocialProviders) {

  public static SetSocialLoginPolicyResponse from(final Organization organization) {
    return new SetSocialLoginPolicyResponse(
        organization.id(),
        organization.socialLoginEnabled(),
        organization.allowedSocialProviders());
  }
}
