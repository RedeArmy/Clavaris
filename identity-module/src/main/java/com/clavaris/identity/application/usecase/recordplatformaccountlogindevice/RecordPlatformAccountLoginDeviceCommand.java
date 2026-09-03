package com.clavaris.identity.application.usecase.recordplatformaccountlogindevice;

import com.clavaris.identity.domain.model.PlatformAccountId;

/**
 * TD-FUT-026: platform-tier mirror of {@code recordaccountlogindevice.
 * RecordAccountLoginDeviceCommand} — see that record's own Javadoc for the meaning of each field,
 * unchanged here beyond {@link PlatformAccountId} replacing {@code AccountId}.
 */
@SuppressWarnings("PMD.LongVariable")
public record RecordPlatformAccountLoginDeviceCommand(
    PlatformAccountId platformAccountId,
    String userAgent,
    String sourceIp,
    String presentedDeviceToken) {

  @Override
  public String toString() {
    return "RecordPlatformAccountLoginDeviceCommand[platformAccountId="
        + platformAccountId
        + ", userAgent="
        + userAgent
        + ", sourceIp="
        + sourceIp
        + ", presentedDeviceToken=[REDACTED]]";
  }
}
