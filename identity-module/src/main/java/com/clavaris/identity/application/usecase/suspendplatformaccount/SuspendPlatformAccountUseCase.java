package com.clavaris.identity.application.usecase.suspendplatformaccount;

/**
 * TD-FUT-031: reversible ban for a {@code PlatformAccount} — {@code PlatformAccount.status}
 * transitions to {@code SUSPENDED}, blocking future logins immediately ({@code
 * AuthenticatePlatformAccountWithPasswordService} already rejects any non-{@code ACTIVE} account)
 * and killing any already-live {@code HttpSession} via {@code PlatformAccountSessionRevoker}.
 * Platform-tier mirror of {@code suspendaccount.SuspendAccountUseCase}, deliberately simpler: no
 * refresh-token/OAuth-session cascade exists to revoke ({@code PlatformAccountSessionRevoker}'s own
 * Javadoc — a {@code PlatformAccount} login is a plain authenticated session, not an OAuth
 * refresh-token chain), and no outbox event (no {@code Organization}/{@code WebhookEndpoint} for a
 * {@code PlatformAccount}-scoped event to ever reach, same reasoning {@code
 * RecordPlatformAccountLoginDeviceService}'s own Javadoc already documents for its own omission).
 */
@FunctionalInterface
public interface SuspendPlatformAccountUseCase {

  /**
   * @throws PlatformAccountNotFoundException if {@code command.platformAccountId()} doesn't exist
   */
  void handle(SuspendPlatformAccountCommand command);
}
