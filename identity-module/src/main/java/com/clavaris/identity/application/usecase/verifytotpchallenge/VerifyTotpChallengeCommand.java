package com.clavaris.identity.application.usecase.verifytotpchallenge;

import com.clavaris.identity.domain.model.AccountId;

public record VerifyTotpChallengeCommand(AccountId accountId, String code) {

  @Override
  public String toString() {
    return "VerifyTotpChallengeCommand[accountId=" + accountId + ", code=<redacted>]";
  }
}
