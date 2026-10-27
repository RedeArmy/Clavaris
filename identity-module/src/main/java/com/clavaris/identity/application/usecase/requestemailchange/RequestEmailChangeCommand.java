package com.clavaris.identity.application.usecase.requestemailchange;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;

public record RequestEmailChangeCommand(
    AccountId accountId, String currentRawPassword, Email newEmail, AuditActor actor) {

  // The raw password never reaches a log line through a stray toString().
  @Override
  public String toString() {
    return "RequestEmailChangeCommand[accountId=" + accountId + ", newEmail=<redacted>]";
  }
}
