package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.domain.model.RedirectPolicy;
import java.time.Instant;
import java.util.UUID;

public record SetRedirectPolicyResponse(
    UUID oauthClientId,
    String fallbackSignInRedirectUrl,
    String fallbackSignUpRedirectUrl,
    String forceSignInRedirectUrl,
    String forceSignUpRedirectUrl,
    Instant updatedAt) {

  public static SetRedirectPolicyResponse from(final RedirectPolicy policy) {
    return new SetRedirectPolicyResponse(
        policy.oauthClientId(),
        policy.fallbackSignInRedirectUrl().orElse(null),
        policy.fallbackSignUpRedirectUrl().orElse(null),
        policy.forceSignInRedirectUrl().orElse(null),
        policy.forceSignUpRedirectUrl().orElse(null),
        policy.updatedAt());
  }
}
