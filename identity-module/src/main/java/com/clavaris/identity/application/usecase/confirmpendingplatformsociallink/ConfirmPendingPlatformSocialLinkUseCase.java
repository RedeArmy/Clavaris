package com.clavaris.identity.application.usecase.confirmpendingplatformsociallink;

import com.clavaris.identity.domain.model.PlatformAccountId;

/** Inbound port — the web adapter depends on this interface, never on the service directly. */
@FunctionalInterface
public interface ConfirmPendingPlatformSocialLinkUseCase {

  /**
   * @return the {@link PlatformAccountId} the confirmed {@code PlatformSocialIdentity} now belongs
   *     to.
   * @throws InvalidPendingPlatformSocialLinkException if the presented token can't be honored
   */
  PlatformAccountId handle(ConfirmPendingPlatformSocialLinkCommand command);
}
