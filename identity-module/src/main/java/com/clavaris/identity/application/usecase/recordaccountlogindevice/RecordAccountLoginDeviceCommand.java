package com.clavaris.identity.application.usecase.recordaccountlogindevice;

import com.clavaris.identity.domain.model.Account;
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
 * @param preloadedAccount TD-PERF-015: the {@link Account} row matching {@code accountId}, when the
 *     caller already has it in hand (every primary-factor controller, moments after its own {@code
 *     Authenticate*UseCase} already loaded it) — {@link RecordAccountLoginDeviceService} uses this
 *     directly instead of its own redundant {@code AccountRepository} lookup when non-null. {@code
 *     null} is the normal, fully-supported case for a caller with no {@link Account} handy (a
 *     resumed device-trust/session-task challenge, or the social-login success handler) — the
 *     4-argument constructor below covers that case explicitly, falling back to this class's own
 *     original always-look-it-up behaviour, unchanged.
 */
// PMD.LongVariable: presentedDeviceToken/preloadedAccount name exactly what they are — same
// convention this codebase's own AuthenticatePlatformAccountWithSocialProviderCommand already
// establishes for this exact class of finding.
@SuppressWarnings("PMD.LongVariable")
public record RecordAccountLoginDeviceCommand(
    AccountId accountId,
    String userAgent,
    String sourceIp,
    String presentedDeviceToken,
    Account preloadedAccount) {

  /** Unchanged, pre-TD-PERF-015 shape — {@link #preloadedAccount} defaults to {@code null}. */
  public RecordAccountLoginDeviceCommand(
      final AccountId accountId,
      final String userAgent,
      final String sourceIp,
      final String presentedDeviceToken) {
    this(accountId, userAgent, sourceIp, presentedDeviceToken, null);
  }

  @Override
  public String toString() {
    return "RecordAccountLoginDeviceCommand[accountId="
        + accountId
        + ", userAgent="
        + userAgent
        + ", sourceIp="
        + sourceIp
        + ", presentedDeviceToken=[REDACTED], preloadedAccount="
        + (preloadedAccount == null ? "null" : "[present]")
        + "]";
  }
}
