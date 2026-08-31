package com.clavaris.identity.application.usecase.recordaccountlogindevice;

import java.util.Optional;

/**
 * Inbound port — the web adapter depends on this interface, never on {@link
 * RecordAccountLoginDeviceService} directly. Called from both tenant login paths ({@code
 * LoginController}, {@code SocialLoginAuthenticationSuccessHandler}) right after a session is
 * established — see {@link RecordAccountLoginDeviceService}'s own Javadoc for why this never
 * throws.
 */
@FunctionalInterface
public interface RecordAccountLoginDeviceUseCase {

  /**
   * @return the raw device token the caller must set as a new {@code DeviceCookie} on the response
   *     — present only when this login didn't already carry a recognized one (a brand-new device,
   *     or an absent/unrecognized cookie); empty when the presented cookie was already valid and
   *     needs no reissue.
   */
  Optional<String> handle(RecordAccountLoginDeviceCommand command);
}
