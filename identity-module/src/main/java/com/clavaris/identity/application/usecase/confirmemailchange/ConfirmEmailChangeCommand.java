package com.clavaris.identity.application.usecase.confirmemailchange;

import com.clavaris.identity.domain.model.AccountId;

/**
 * {@code accountId} is the signed-in Account opening the link: the link only works for the Account
 * that asked for the change, so a forwarded or leaked link is useless to anyone else.
 */
public record ConfirmEmailChangeCommand(AccountId accountId, String rawToken) {

  @Override
  public String toString() {
    return "ConfirmEmailChangeCommand[accountId=" + accountId + ", rawToken=<redacted>]";
  }
}
