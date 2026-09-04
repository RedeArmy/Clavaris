package com.clavaris.identity.application.usecase.requestemailverification;

/**
 * ADR-0024: identity-module's own mirror of organization-module's identically-named enum — module
 * independence, same "plain mirror, not a shared type" rule {@code Organization
 * .allowedSocialProviders} (plain strings) already establishes for its own cross-module concern.
 * {@link AccountAuthenticationPolicyProvider}'s own bridge implementation (app module) is the one
 * place that translates between the two.
 */
public enum EmailVerificationMethod {
  LINK,
  CODE,
  BOTH
}
