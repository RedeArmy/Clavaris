package com.clavaris.identity.application.usecase.confirmdevicetrustchallenge;

import com.clavaris.identity.domain.model.AccountId;

/**
 * @param accountId resolved from the pending-challenge session state the issuing controller stored,
 *     never a client-supplied value — see {@code DeviceTrustChallengeController}'s own Javadoc.
 * @param presentedRawCode never the hash, never persisted as-is.
 */
public record ConfirmDeviceTrustChallengeCommand(AccountId accountId, String presentedRawCode) {

  /** BR-ID-01: same redaction rationale as every other code-carrying command in this codebase. */
  @Override
  public String toString() {
    return "ConfirmDeviceTrustChallengeCommand[accountId="
        + accountId
        + ", presentedRawCode=[REDACTED]]";
  }
}
