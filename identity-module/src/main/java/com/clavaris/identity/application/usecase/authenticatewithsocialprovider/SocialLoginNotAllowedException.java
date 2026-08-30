package com.clavaris.identity.application.usecase.authenticatewithsocialprovider;

import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SocialProvider;

/**
 * ADR-0020 Decision 3, BR-ID-12: thrown when {@link OrganizationSocialLoginPolicyProvider} says the
 * given {@link SocialProvider} is not enabled for the given {@link OrganizationId} — checked at the
 * point of use, not only at an earlier UI-level gate (see that port's own Javadoc).
 */
public final class SocialLoginNotAllowedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public SocialLoginNotAllowedException(
      final OrganizationId organizationId, final SocialProvider provider) {
    super("Social login via " + provider + " is not enabled for organization " + organizationId);
  }
}
