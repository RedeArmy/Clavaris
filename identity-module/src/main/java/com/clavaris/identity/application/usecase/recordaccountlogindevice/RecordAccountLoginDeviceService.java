package com.clavaris.identity.application.usecase.recordaccountlogindevice;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.BestEffortEventPublisher;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.requestemailverification.MailDeliveryException;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.domain.event.AccountNewDeviceDetectedEvent;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.KnownDevice;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Orchestration for {@link RecordAccountLoginDeviceUseCase}. Recognized device (a valid presented
 * {@code DeviceCookie}) → {@link KnownDevice#touch()} + save, no new cookie needed. Unrecognized or
 * absent cookie → mint a fresh device token, persist the new {@link KnownDevice} row, attempt the
 * "new device login" notification email, and hand the caller the raw token to set as a new cookie.
 *
 * <p><b>Never lets a side-channel write fail an otherwise-successful login</b> — a deliberate
 * departure from {@code RequestEmailVerificationService}'s own style (there, a failed send *is* the
 * point of the request). The device row is persisted, and its token returned, before any of the
 * audit/outbox/mail steps below run — a failure in any of them never loses that bookkeeping.
 * Neither {@code LoginController} nor {@code SocialLoginAuthenticationSuccessHandler} wraps this
 * use case's own {@code handle} call in a try/catch, trusting this guarantee.
 *
 * <p><b>TD-SEC-033:</b> device recognition uses an opaque, unforgeable device token, not the raw
 * {@code User-Agent} header — see {@code KnownDevice}'s own Javadoc for why. The {@link
 * DataIntegrityViolationException} catch below is defense-in-depth against a statistically
 * negligible token collision, kept from the era this class was still User-Agent-keyed.
 *
 * <p><b>TD-SEC-036:</b> audit, account lookup, and outbox write are each isolated independently
 * (via {@link BestEffortEventPublisher} for the outbox, a local try/catch for the rest) — see
 * technical-debt-register.md TD-SEC-036 for the full incident history.
 *
 * <p><b>Code review finding (2026-09-01), migration grandfather suppression:</b> the {@code
 * V20260831100000} migration means no existing browser has ever received a {@code DeviceCookie} —
 * every already-registered Account's very next login is otherwise indistinguishable from a genuine
 * new device, which would notify the entire existing user base in one wave on deploy day (reading
 * as a mass-compromise alert, not a routine upgrade). {@code deviceCookieMigrationCutoverAt}
 * (default: that migration's own timestamp) draws the line: an Account created before it, whose
 * very first {@link KnownDevice} row is only now being created, gets the row (still recognized
 * normally from then on) but no outbox event or notification for this one, migration-artifact
 * occurrence — a genuinely new device for that same Account next month still notifies as normal. An
 * Account created after the cutover is a real signup with no migration artifact to suppress.
 */
// LongVariable: deviceCookieMigrationCutoverAt (field/param/bean-method-param) and the two local
// booleans in handle() below are all long by design, not accidentally — same "deliberate
// record-style naming" precedent as KnownDevice's own class-level suppression.
@SuppressWarnings("PMD.LongVariable")
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
  private final EventOutboxWriter outbox;
  private final Instant deviceCookieMigrationCutoverAt;

  @SuppressWarnings("java:S107") // one parameter per collaborating port — same rationale as
  // DeleteAccountService's own identical suppression.
  public RecordAccountLoginDeviceService(
      final KnownDeviceRepository knownDevices,
      final AccountRepository accounts,
      final MailSender mailSender,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox,
      final Instant deviceCookieMigrationCutoverAt) {
    this.knownDevices = knownDevices;
    this.accounts = accounts;
    this.mailSender = mailSender;
    this.auditEvents = auditEvents;
    this.outbox = outbox;
    this.deviceCookieMigrationCutoverAt = deviceCookieMigrationCutoverAt;
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

    // Captured before the insert below — after it, this account always has at least one row (the
    // one just inserted), so "had zero before this" can only be answered now.
    final boolean isAccountsFirstEverKnownDevice =
        !knownDevices.existsByAccountId(command.accountId());

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
      // colliding) — degrades to "no notification this time" rather than an unhandled 500.
      LOG.warn("event=known_device_token_collision", e);
      return Optional.empty();
    }

    recordAudit(command.accountId(), device.id());

    // Defensive only — a real Account is guaranteed to exist right after it just authenticated;
    // a thrown or genuinely unresolved lookup both degrade to null, skipping outbox and mail.
    final Account account = findAccountOrNull(command.accountId());
    if (account != null) {
      // Migration grandfather (see class Javadoc): this Account predates the cookie mechanism
      // itself, so its very first row here is an artifact of the migration, not a real new
      // device — the row still stands (recognized normally from here on), but this one occurrence
      // gets no outbox event or notification.
      final boolean isMigrationArtifact =
          isAccountsFirstEverKnownDevice
              && account.createdAt().isBefore(deviceCookieMigrationCutoverAt);
      if (isMigrationArtifact) {
        LOG.info("event=new_device_notification_suppressed_migration_grandfather");
      } else {
        BestEffortEventPublisher.publish(
            LOG,
            outbox,
            "account.new_device_detected",
            command.accountId(),
            account.organizationId(),
            AccountNewDeviceDetectedEvent.from(device, account.organizationId()),
            "event=account_new_device_detected_outbox_write_failed");
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
    }

    return Optional.of(rawDeviceToken);
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException") // AuditEventRecorder can throw a Spring
  // DataAccessException like any other DB write; must never propagate (see class Javadoc).
  private void recordAudit(final AccountId accountId, final UUID deviceId) {
    try {
      auditEvents.write(
          AuditActor.account(accountId.value()),
          "account.new_device_detected",
          "KnownDevice",
          deviceId.toString(),
          null);
    } catch (final RuntimeException e) {
      LOG.warn("event=account_new_device_detected_audit_write_failed", e);
    }
  }

  // Two genuinely distinct exits (resolved / lookup failed), same "each outcome needs its own
  // exit" rationale as this class's own handle() method above.
  @SuppressWarnings({
    "PMD.AvoidCatchingGenericException", // AccountRepository#findById can throw a Spring
    // DataAccessException like any other read; must never propagate (see class Javadoc).
    "PMD.OnlyOneReturn"
  })
  private Account findAccountOrNull(final AccountId accountId) {
    try {
      return accounts.findById(accountId).orElse(null);
    } catch (final RuntimeException e) {
      LOG.warn("event=account_new_device_detected_account_lookup_failed", e);
      return null;
    }
  }

  private static String normalizedUserAgent(final String userAgent) {
    return userAgent == null || userAgent.isBlank() ? UNKNOWN_USER_AGENT : userAgent;
  }
}
