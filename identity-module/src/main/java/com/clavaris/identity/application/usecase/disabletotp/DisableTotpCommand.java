package com.clavaris.identity.application.usecase.disabletotp;

import com.clavaris.identity.domain.model.AccountId;

/** {@code code} is a current authenticator code or an unspent recovery code. */
public record DisableTotpCommand(AccountId accountId, String code) {

  @Override
  public String toString() {
    return "DisableTotpCommand[accountId=" + accountId + ", code=<redacted>]";
  }
}
