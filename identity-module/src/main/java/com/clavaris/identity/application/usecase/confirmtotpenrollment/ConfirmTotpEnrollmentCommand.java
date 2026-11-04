package com.clavaris.identity.application.usecase.confirmtotpenrollment;

import com.clavaris.identity.domain.model.AccountId;

public record ConfirmTotpEnrollmentCommand(AccountId accountId, String code) {

  @Override
  public String toString() {
    return "ConfirmTotpEnrollmentCommand[accountId=" + accountId + ", code=<redacted>]";
  }
}
