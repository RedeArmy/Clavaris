package com.clavaris.identity.application.usecase.changepassword;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.AccountId;

/**
 * TD-FUT-043: an End-User changing their own password from a signed-in session, proving they know
 * the current one first. Not the forgot-password flow (an emailed token for someone who does not).
 *
 * @param keepSessionId the id of the browser session making the request, which stays signed in;
 *     every other session of the Account is ended. {@code null} ends all of them.
 */
public record ChangePasswordCommand(
    AccountId accountId,
    String currentRawPassword,
    String newRawPassword,
    String keepSessionId,
    AuditActor actor,
    boolean recentlySignedIn) {

  // BR-ID-01: a record's generated toString() would print both passwords verbatim.
  @Override
  public String toString() {
    return "ChangePasswordCommand[accountId="
        + accountId
        + ", currentRawPassword=[REDACTED], newRawPassword=[REDACTED], keepSessionId=[REDACTED]]";
  }
}
