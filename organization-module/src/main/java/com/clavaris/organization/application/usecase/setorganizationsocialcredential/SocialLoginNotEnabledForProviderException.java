package com.clavaris.organization.application.usecase.setorganizationsocialcredential;

import com.clavaris.organization.domain.model.SocialProvider;
import java.util.UUID;

/**
 * ADR-0020 Decision 3's own opt-in gate (BR-ID-12) still governs whether social login is offered at
 * all for a given provider — ADR-0022 (bring-your-own credentials) only changes which app's
 * credentials are used once that gate is already open, it never bypasses it. Setting credentials
 * for a provider the Organization hasn't allowed via {@code Organization.allowedSocialProviders}
 * would silently do nothing useful (no login path ever reaches this provider), so it's rejected
 * outright rather than left as a confusing dead configuration.
 */
public final class SocialLoginNotEnabledForProviderException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public SocialLoginNotEnabledForProviderException(
      final UUID organizationId, final SocialProvider provider) {
    super(
        "Organization "
            + organizationId
            + " has not enabled social login for provider "
            + provider
            + " (ADR-0020 Decision 3) — enable it first via the social-login-policy endpoint");
  }
}
