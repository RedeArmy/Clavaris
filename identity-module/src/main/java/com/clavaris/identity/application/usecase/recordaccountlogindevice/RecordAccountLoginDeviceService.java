package com.clavaris.identity.application.usecase.recordaccountlogindevice;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.BestEffortEventPublisher;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.domain.event.AccountNewDeviceDetectedEvent;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.KnownDevice;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
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
 * <p><b>TD-PERF-011 (closed):</b> the notification email used to be sent synchronously, unwrapped,
 * on the calling thread — unlike the outbox write one line earlier ({@link
 * BestEffortEventPublisher}, a fast local DB insert), this is a real outbound HTTP call to Resend
 * that can take up to {@code ResendHttpClient}'s own 10s timeout, on the single highest-traffic
 * endpoint in the system (every first-ever and every new-device login). {@link
 * #newDeviceNotificationExecutor} is a small, bounded, daemon-thread pool dedicated to this one
 * side effect — the send is submitted there and this method returns immediately, the same "don't
 * block the calling thread on a third-party HTTP call" pattern TD-PERF-004 already established for
 * webhook delivery. Deliberately NOT the pattern to reach for on a passwordless sign-in method
 * (email code/link): there, the equivalent send is not a side effect at all, it is the entire
 * mechanism — the caller genuinely needs to know delivery was attempted before responding, so those
 * flows stay synchronous by design and are out of this row's scope.
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
// AvoidDuplicateLiterals: "PMD.AvoidCatchingGenericException" repeats across this class's several
// defensive catch sites, same rationale as EmailLinkSignInController's own class-level suppression
// of this exact rule — extracting a constant for a suppression string would be needless
// indirection.
@SuppressWarnings({"PMD.LongVariable", "PMD.AvoidDuplicateLiterals"})
public class RecordAccountLoginDeviceService implements RecordAccountLoginDeviceUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(RecordAccountLoginDeviceService.class);

  // A non-browser/UA-stripping client is still a real device worth tracking under the
  // cookie-based design (unlike the old UA-keyed one, this mechanism doesn't depend on the
  // header at all for its security property) — this is display/audit filler only.
  @SuppressWarnings("PMD.LongVariable")
  private static final String UNKNOWN_USER_AGENT = "Unknown";

  // TD-FUT-025: a week is deliberately generous compared to VerificationToken's other, more
  // time-sensitive uses (password reset's own 30 minutes) — this link's whole premise is that the
  // account holder may not check this specific email right away, unlike a reset they just
  // requested themselves. Still finite: past a week, the normal remedy (log in, review/revoke
  // sessions from the account's own sessions page, BR-ID-13) is the expected path instead.
  private static final Duration NEW_DEVICE_ALERT_TOKEN_TTL = Duration.ofDays(7);

  private final KnownDeviceRepository knownDevices;
  private final AccountRepository accounts;
  private final MailSender mailSender;
  private final AuditEventRecorder auditEvents;
  private final EventOutboxWriter outbox;
  private final Instant deviceCookieMigrationCutoverAt;
  private final Executor newDeviceNotificationExecutor;
  private final VerificationTokenRepository verificationTokens;

  @SuppressWarnings("java:S107") // one parameter per collaborating port — same rationale as
  // DeleteAccountService's own identical suppression.
  public RecordAccountLoginDeviceService(
      final KnownDeviceRepository knownDevices,
      final AccountRepository accounts,
      final MailSender mailSender,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox,
      final Instant deviceCookieMigrationCutoverAt,
      final Executor newDeviceNotificationExecutor,
      final VerificationTokenRepository verificationTokens) {
    this.knownDevices = knownDevices;
    this.accounts = accounts;
    this.mailSender = mailSender;
    this.auditEvents = auditEvents;
    this.outbox = outbox;
    this.deviceCookieMigrationCutoverAt = deviceCookieMigrationCutoverAt;
    this.newDeviceNotificationExecutor = newDeviceNotificationExecutor;
    this.verificationTokens = verificationTokens;
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

    // TD-PERF-015: uses the caller's own already-loaded Account when it supplied one — see
    // RecordAccountLoginDeviceCommand's own Javadoc — falling back to this class's original
    // lookup, unchanged, for a caller with none in hand. Defensive either way: a real Account is
    // guaranteed to exist right after it just authenticated; a thrown or genuinely unresolved
    // lookup both degrade to null, skipping outbox and mail.
    final Account account =
        command.preloadedAccount() != null
            ? command.preloadedAccount()
            : findAccountOrNull(command.accountId());
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
        // TD-FUT-025: minted and persisted here, on the calling thread, same as the outbox write
        // immediately above — never inside the background executor below, so a rejected/never-run
        // notification task still leaves a real, usable token behind rather than silently minting
        // one that's never actually sent. A failure here degrades to no action link in the email
        // (rawAlertToken null, same "never lets a side-channel write fail an otherwise-successful
        // login" guarantee this class's own Javadoc establishes) rather than losing the login.
        final String rawAlertToken = mintNewDeviceAlertTokenOrNull(command.accountId());
        // TD-PERF-011: fire-and-forget — this method returns as soon as the task is enqueued,
        // not once Resend actually responds. See this class's own Javadoc for why.
        try {
          newDeviceNotificationExecutor.execute(
              () -> sendNewDeviceNotification(account, device, command.sourceIp(), rawAlertToken));
        } catch (final RejectedExecutionException e) {
          // Same "never lets a side-channel write fail an otherwise-successful login" guarantee
          // this class's own Javadoc establishes — only realistically reachable during process
          // shutdown, once the executor itself has already been shut down.
          LOG.warn("event=new_device_notification_rejected", e);
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

  // TD-FUT-025: same "never lets a side-channel write fail an otherwise-successful login"
  // guarantee as recordAudit/findAccountOrNull above — a failure here degrades to an
  // informational-only email (null raw token, see sendNewDeviceNotification below), not a lost
  // login.
  // Two genuinely distinct exits (minted / mint failed), same "each outcome needs its own exit"
  // rationale as findAccountOrNull's own identical suppression above.
  @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
  private String mintNewDeviceAlertTokenOrNull(final AccountId accountId) {
    try {
      final String rawToken = RefreshTokenSecret.generateRawValue();
      final VerificationToken token =
          VerificationToken.issue(
              accountId,
              VerificationTokenType.NEW_DEVICE_LOGIN_ALERT,
              RefreshTokenSecret.hash(rawToken),
              Instant.now().plus(NEW_DEVICE_ALERT_TOKEN_TTL));
      verificationTokens.save(token);
      return rawToken;
    } catch (final RuntimeException e) {
      LOG.warn("event=new_device_alert_token_mint_failed", e);
      return null;
    }
  }

  // TD-PERF-011: runs on newDeviceNotificationExecutor's own background thread, never the request
  // thread — no caller left up there to catch anything, so any RuntimeException MailSender's own
  // contract doesn't explicitly document (not just the documented MailDeliveryException) must be
  // caught and logged right here, or it silently escapes to the executor's default
  // UncaughtExceptionHandler instead of this class's own logging — same defensive posture as
  // recordAudit/findAccountOrNull above, just relocated off the request thread.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private void sendNewDeviceNotification(
      final Account account,
      final KnownDevice device,
      final String sourceIp,
      final String rawAlertToken) {
    try {
      mailSender.sendNewDeviceLoginNotification(
          account.email().value(),
          account.organizationId(),
          device.userAgent(),
          sourceIp,
          device.firstSeenAt(),
          rawAlertToken);
    } catch (final RuntimeException e) {
      // BR-DATA-01: status/event only, never the recipient address or any other PII.
      LOG.warn("event=new_device_notification_failed", e);
    }
  }

  private static String normalizedUserAgent(final String userAgent) {
    return userAgent == null || userAgent.isBlank() ? UNKNOWN_USER_AGENT : userAgent;
  }
}
