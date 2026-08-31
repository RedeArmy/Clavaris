package com.clavaris.identity.application.usecase.confirmpendingsociallink;

import com.clavaris.identity.domain.model.AccountId;

/**
 * Inbound port — the web adapter depends on this interface, never on {@link
 * ConfirmPendingSocialLinkService} directly.
 */
@FunctionalInterface
public interface ConfirmPendingSocialLinkUseCase {

  /**
   * @return the {@link AccountId} the confirmed {@code SocialIdentity} now belongs to — the caller
   *     may use this to establish a session immediately, same as a fresh login would.
   * @throws InvalidPendingSocialLinkException if the presented token can't be honored
   */
  AccountId handle(ConfirmPendingSocialLinkCommand command);
}
