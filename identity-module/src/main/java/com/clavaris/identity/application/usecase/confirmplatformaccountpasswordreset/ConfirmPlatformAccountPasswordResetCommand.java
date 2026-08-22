package com.clavaris.identity.application.usecase.confirmplatformaccountpasswordreset;

public record ConfirmPlatformAccountPasswordResetCommand(
    String presentedRawToken, String newRawPassword) {

  @Override
  public String toString() {
    return "ConfirmPlatformAccountPasswordResetCommand[presentedRawToken=[REDACTED],"
        + " newRawPassword=[REDACTED]]";
  }
}
