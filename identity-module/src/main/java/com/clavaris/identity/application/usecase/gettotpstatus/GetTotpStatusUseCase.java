package com.clavaris.identity.application.usecase.gettotpstatus;

import com.clavaris.identity.domain.model.AccountId;

/** ADR-0040: what the Account's own page needs to show about its authenticator app. */
@FunctionalInterface
public interface GetTotpStatusUseCase {

  TotpStatus handle(AccountId accountId);

  /**
   * @param availableToOrganization the Organization has turned the authenticator app on
   * @param enrolled the Account has a confirmed one
   * @param unusedRecoveryCodes how many recovery codes are left, 0 when not enrolled
   */
  record TotpStatus(boolean availableToOrganization, boolean enrolled, long unusedRecoveryCodes) {}
}
