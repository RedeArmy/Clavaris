package com.clavaris.identity.application.usecase.recordplatformaccountlogindevice;

import java.util.Optional;

/**
 * Inbound port — TD-FUT-026, platform-tier mirror of {@code recordaccountlogindevice.
 * RecordAccountLoginDeviceUseCase}. Called from {@code PlatformLoginController} right after a
 * session is established.
 */
@FunctionalInterface
public interface RecordPlatformAccountLoginDeviceUseCase {

  /** Same return-value contract as {@code RecordAccountLoginDeviceUseCase.handle}. */
  Optional<String> handle(RecordPlatformAccountLoginDeviceCommand command);
}
