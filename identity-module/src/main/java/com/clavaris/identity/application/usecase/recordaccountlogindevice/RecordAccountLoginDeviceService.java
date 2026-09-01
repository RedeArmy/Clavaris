package com.clavaris.identity.application.usecase.recordaccountlogindevice;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.MailDeliveryException;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.KnownDevice;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Orchestration for {@link RecordAccountLoginDeviceUseCase}. Recognized device (a valid presented
 * {@code DeviceCookie}) → {@link KnownDevice#touch()} + save, no new cookie needed. Unrecognized or
 * absent cookie → mint a fresh device token, persist the new {@link KnownDevice} row, attempt the
 * "new device login" notification email, and hand the caller the raw token to set as a new cookie.
 *
 * <p><b>Never lets a mail failure propagate</b> — a deliberate departure from {@code
 * RequestEmailVerificationService}'s own style (which lets {@link MailDeliveryException}
 * propagate): there, a failed send *is* the whole point of that request and the caller needs to
 * know. Here, the caller is mid-login (this runs right after {@code
 * AuthenticatedSessionEstablisher#establish}/{@code establishViaSocialLogin} already succeeded) — a
 * Resend hiccup must never turn an otherwise-successful login into a failed request. The device row
 * is persisted, and its token returned for the caller to set as a cookie, before the send attempt —
 * a failed notification never also loses the "we've now seen this device" bookkeeping.
 *
 * <p><b>TD-SEC-033 (SDE-III review, 2026-08-31):</b> device recognition moved from the raw {@code
 * User-Agent} header to an opaque, unforgeable device token — see {@code KnownDevice}'s own Javadoc
 * for why. One structural side effect worth calling out explicitly: the classic TOCTOU race this
 * class used to guard against (two concurrent logins from the same brand-new {@code (accountId,
 * userAgent)} both racing into the insert) can no longer actually happen the same way — each
 * concurrent request now mints its own independent, high-entropy random token, so there is no
 * shared key for two requests to collide on. The {@link DataIntegrityViolationException} catch
 * below is kept anyway, purely as defense-in-depth against a statistically negligible token
 * collision (or any other cause of that exact constraint firing) — see its own comment.
 */
public class RecordAccountLoginDeviceService implements RecordAccountLoginDeviceUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(RecordAccountLoginDeviceService.class);

  // A non-browser/UA-stripping client is still a real device worth tracking under the
  // cookie-based design (unlike the old UA-keyed one, this mechanism doesn't depend on the
  // header at all for its security property) — this is display/audit filler only.
  @SuppressWarnings("PMD.LongVariable")
  private static final String UNKNOWN_USER_AGENT = "Unknown";

  private final KnownDeviceRepository knownDevices;
  private final AccountRepository accounts;
  private final MailSender mailSender;
  private final AuditEventRecorder auditEvents;

  public RecordAccountLoginDeviceService(
      final KnownDeviceRepository knownDevices,
      final AccountRepository accounts,
      final MailSender mailSender,
      final AuditEventRecorder auditEvents) {
    this.knownDevices = knownDevices;
    this.accounts = accounts;
    this.mailSender = mailSender;
    this.auditEvents = auditEvents;
  }

  // Three genuinely distinct outcomes (recognized via cookie / lost the negligible token-
  // collision race / newly-seen device, notified), each with its own exit — same "each outcome
  // needs its own exit" rationale as e.g. RegisterAccountController's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Override
  public Optional<String> handle(final RecordAccountLoginDeviceCommand command) {
    final String presentedToken = command.presentedDeviceToken();
    if (presentedToken != null && !presentedToken.isBlank()) {
      final Optional<KnownDevice> recognized =
          knownDevices.findByAccountIdAndDeviceTokenHash(
              command.accountId(), RefreshTokenSecret.hash(presentedToken));
      if (recognized.isPresent()) {
        final KnownDevice device = recognized.get();
        device.touch();
        knownDevices.save(device);
        return Optional.empty(); // the presented cookie is still valid — nothing new to set
      }
      // Presented but unrecognized (stale, tampered, foreign, or an already-purged row) — falls
      // through to "new device" below, same as never having presented one at all.
    }

    final String rawDeviceToken = RefreshTokenSecret.generateRawValue();
    final KnownDevice device =
        KnownDevice.recognize(
            command.accountId(),
            normalizedUserAgent(command.userAgent()),
            RefreshTokenSecret.hash(rawDeviceToken));
    try {
      knownDevices.save(device);
    } catch (final DataIntegrityViolationException e) {
      // Statistically negligible under this design (two independent 256-bit random values
      // colliding) — a real hit here would mean something is actually wrong (e.g. a broken
      // SecureRandom), not an expected, benign race the old UA-keyed version of this catch used
      // to recover from. Degrades to "no notification this time" rather than an unhandled 500,
      // same "never let this path fail an otherwise-successful login" guarantee this class's own
      // header paragraph establishes.
      LOG.warn("event=known_device_token_collision", e);
      return Optional.empty();
    }

    auditEvents.write(
        AuditActor.account(command.accountId().value()),
        "account.new_device_detected",
        "KnownDevice",
        device.id().toString(),
        null);

    // Defensive only — a real Account is guaranteed to exist by the time this runs (this is only
    // ever called right after that same Account just authenticated); an unresolvable id here
    // would mean something upstream is already badly broken, not a case worth blocking the new
    // cookie on.
    final Account account = accounts.findById(command.accountId()).orElse(null);
    if (account != null) {
      try {
        mailSender.sendNewDeviceLoginNotification(
            account.email().value(),
            account.organizationId(),
            device.userAgent(),
            command.sourceIp(),
            device.firstSeenAt());
      } catch (final MailDeliveryException e) {
        // BR-DATA-01: status/event only, never the recipient address or any other PII.
        LOG.warn("event=new_device_notification_failed", e);
      }
    }

    return Optional.of(rawDeviceToken);
  }

  private static String normalizedUserAgent(final String userAgent) {
    return userAgent == null || userAgent.isBlank() ? UNKNOWN_USER_AGENT : userAgent;
  }
}
