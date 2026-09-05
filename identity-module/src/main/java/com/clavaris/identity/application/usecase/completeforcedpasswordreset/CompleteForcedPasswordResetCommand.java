package com.clavaris.identity.application.usecase.completeforcedpasswordreset;

import com.clavaris.identity.domain.model.AccountId;

public record CompleteForcedPasswordResetCommand(AccountId accountId, String newRawPassword) {

  /** BR-ID-01 rationale — same as every other command that carries a raw password. */
  @Override
  public String toString() {
    return "CompleteForcedPasswordResetCommand[accountId="
        + accountId
        + ", newRawPassword=[REDACTED]]";
  }
}
