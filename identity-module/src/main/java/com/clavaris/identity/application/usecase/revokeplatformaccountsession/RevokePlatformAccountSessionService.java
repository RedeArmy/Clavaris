package com.clavaris.identity.application.usecase.revokeplatformaccountsession;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.listactivesessionsforplatformaccount.ActivePlatformAccountSession;
import com.clavaris.identity.application.usecase.listactivesessionsforplatformaccount.PlatformAccountActiveSessionsRepository;
import com.clavaris.identity.domain.model.PlatformAccountId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestration for {@link RevokePlatformAccountSessionUseCase} — TD-FUT-026, platform-tier mirror
 * of {@code revokeaccountsession.RevokeAccountSessionService}. Same ownership-check-via-lookup
 * shape, same "revoke first, unconditionally, then best-effort record it" ordering.
 *
 * <p>Deliberately narrower than its tenant-tier mirror: audits the revocation but publishes no
 * outbox event — same "no Organization for any WebhookEndpoint to be scoped to" reasoning {@link
 * com.clavaris.identity.application.usecase.recordplatformaccountlogindevice.
 * RecordPlatformAccountLoginDeviceService}'s own Javadoc already gives for the same omission.
 */
public class RevokePlatformAccountSessionService implements RevokePlatformAccountSessionUseCase {

  private static final Logger LOG =
      LoggerFactory.getLogger(RevokePlatformAccountSessionService.class);

  private final PlatformAccountActiveSessionsRepository activeSessions;
  private final AuditEventRecorder auditEvents;

  public RevokePlatformAccountSessionService(
      final PlatformAccountActiveSessionsRepository activeSessions,
      final AuditEventRecorder auditEvents) {
    this.activeSessions = activeSessions;
    this.auditEvents = auditEvents;
  }

  @Override
  public void handle(final RevokePlatformAccountSessionCommand command) {
    final ActivePlatformAccountSession session =
        activeSessions
            .findByPlatformAccountIdAndSessionId(command.platformAccountId(), command.sessionId())
            .orElseThrow(() -> new PlatformAccountSessionNotFoundException(command.sessionId()));

    // The real, irreversible action, first and unconditionally — never gated on Postgres health,
    // same ordering as RevokeAccountSessionService's own identical call.
    activeSessions.revoke(session.sessionId());

    recordRevocation(command.platformAccountId(), session.sessionId());
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private void recordRevocation(final PlatformAccountId platformAccountId, final String sessionId) {
    try {
      auditEvents.write(
          AuditActor.platformAccount(platformAccountId.value()),
          "platform_account.session_revoked",
          "Session",
          sessionId,
          null);
    } catch (final RuntimeException e) {
      LOG.warn("event=platform_account_session_revoked_audit_write_failed", e);
    }
  }
}
