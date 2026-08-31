package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.domain.model.Organization;
import java.util.List;
import java.util.UUID;

// PMD.LongVariable: socialLoginEnabled/allowedSocialProviders mirror Organization's own field
// names exactly, same convention SetSocialLoginPolicyResponse's own sibling records follow — a
// shortened name here would only make this response DTO harder to correlate with the domain type
// it mirrors.
@SuppressWarnings("PMD.LongVariable")
public record SetSocialLoginPolicyResponse(
    UUID organizationId, boolean socialLoginEnabled, List<String> allowedSocialProviders) {

  public static SetSocialLoginPolicyResponse from(final Organization organization) {
    return new SetSocialLoginPolicyResponse(
        organization.id(),
        organization.socialLoginEnabled(),
        organization.allowedSocialProviders());
  }
}
