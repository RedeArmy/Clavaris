package com.clavaris.identity.application.usecase.confirmdevicetrustchallenge;

import com.clavaris.identity.domain.model.AccountId;

public interface ConfirmDeviceTrustChallengeUseCase {

  /**
   * @throws InvalidDeviceTrustChallengeException on any invalid/expired/wrong-owner code.
   * @throws com.clavaris.identity.application.usecase.codelockout.CodeTemporarilyLockedException
   *     while the verification is paused for the Account (ADR-0033).
   */
  void handle(ConfirmDeviceTrustChallengeCommand command);

  /**
   * Whether the verification is paused for the Account. Asked when the page is shown: no code is
   * sent while it is paused, so the page must not claim one was.
   */
  boolean isPaused(AccountId accountId);
}
