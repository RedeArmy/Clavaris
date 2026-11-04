package com.clavaris.identity.application.usecase.regeneraterecoverycodes;

import com.clavaris.identity.domain.model.AccountId;

/** {@code code} is a current authenticator code; a recovery code would be spent by the check. */
public record RegenerateRecoveryCodesCommand(AccountId accountId, String code) {

  @Override
  public String toString() {
    return "RegenerateRecoveryCodesCommand[accountId=" + accountId + ", code=<redacted>]";
  }
}
