package com.clavaris.identity.application.usecase.recordaccountlogindevice;

/**
 * Inbound port — the web adapter depends on this interface, never on {@link
 * RecordAccountLoginDeviceService} directly. Called from both tenant login paths ({@code
 * LoginController}, {@code SocialLoginAuthenticationSuccessHandler}) right after a session is
 * established — see {@link RecordAccountLoginDeviceService}'s own Javadoc for why this never
 * throws.
 */
@FunctionalInterface
public interface RecordAccountLoginDeviceUseCase {

  void handle(RecordAccountLoginDeviceCommand command);
}
