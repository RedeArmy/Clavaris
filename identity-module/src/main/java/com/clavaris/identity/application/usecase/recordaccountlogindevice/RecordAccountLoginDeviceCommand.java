package com.clavaris.identity.application.usecase.recordaccountlogindevice;

import com.clavaris.identity.domain.model.AccountId;

/**
 * @param accountId the just-authenticated Account — always the use case's own result, never client
 *     input.
 * @param userAgent the raw {@code User-Agent} request header at login time; display/audit only
 *     since TD-SEC-033 — no longer the device-recognition match key (that's {@code
 *     presentedDeviceToken} below). May be {@code null} or blank for a client that sent none —
 *     {@link RecordAccountLoginDeviceService} normalizes it to a placeholder rather than treating
 *     it as a no-op, unlike the pre-TD-SEC-033 version of this class.
 * @param sourceIp the raw {@code request.getRemoteAddr()} at login time — same plain-extraction
 *     caveat {@code RateLimitIdentifiers.sourceIp} already documents (no reverse proxy in front of
 *     this deployment yet); informational only, included in the notification email.
 * @param presentedDeviceToken the raw value of the {@code DeviceCookie} the request arrived with —
 *     {@code null} when no such cookie was presented at all (first-ever visit from this browser,
 *     cookies cleared/blocked, or a non-browser client). Never logged or persisted as-is — only its
 *     hash is ever compared against, same principle {@code RegisterAccountCommand}'s own {@code
 *     rawPassword} already establishes for this class's own redacting {@link #toString()}.
 */
// PMD.LongVariable: presentedDeviceToken names exactly what it is — same convention this
// codebase's own AuthenticatePlatformAccountWithSocialProviderCommand already establishes for
// this exact class of finding.
@SuppressWarnings("PMD.LongVariable")
public record RecordAccountLoginDeviceCommand(
    AccountId accountId, String userAgent, String sourceIp, String presentedDeviceToken) {

  @Override
  public String toString() {
    return "RecordAccountLoginDeviceCommand[accountId="
        + accountId
        + ", userAgent="
        + userAgent
        + ", sourceIp="
        + sourceIp
        + ", presentedDeviceToken=[REDACTED]]";
  }
}
