package com.clavaris.identity.application.usecase.recordplatformaccountlogindevice;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.MailDeliveryException;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.PlatformMailSender;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.PlatformVerificationTokenRepository;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.domain.model.PlatformKnownDevice;
import com.clavaris.identity.domain.model.PlatformVerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * TD-FUT-026 (closed 2026-09-02): platform-tier mirror of {@code recordaccountlogindevice.
 * RecordAccountLoginDeviceService} — same recognized/unrecognized-device logic, same
 * never-lets-a-side-channel-write-fail-an-otherwise-successful-login guarantee, same migration
 * grandfather reasoning (every {@code PlatformAccount} that already existed before {@code
 * platformKnownDeviceMigrationCutoverAt} gets its first-ever row silently, no notification —
 * otherwise every operator's very next login after this feature deploys would read as a
 * mass-compromise alert). See that class's own Javadoc for the full rationale, unchanged here
 * beyond the type substitution and one deliberate omission: no outbox/webhook event is published —
 * {@code PlatformAccount} activity belongs to no {@code Organization} for any {@code
 * WebhookEndpoint} (ADR-0007) to ever be scoped to, so there is no real consumer for one to reach.
 *
 * <p><b>TD-FUT-031 (closed):</b> the notification carries a real "this wasn't me" action link now,
 * same {@code VerificationTokenType#NEW_DEVICE_LOGIN_ALERT} mechanism TD-FUT-025 built for the
 * tenant tier — see {@code mintNewDeviceAlertTokenOrNull}/{@code
 * confirmnewplatformdeviceloginalert.ConfirmNewPlatformDeviceLoginAlertService}.
 */
@SuppressWarnings("PMD.LongVariable")
public class RecordPlatformAccountLoginDeviceService
    implements RecordPlatformAccountLoginDeviceUseCase {

  private static final Logger LOG =
      LoggerFactory.getLogger(RecordPlatformAccountLoginDeviceService.class);

  @SuppressWarnings("PMD.LongVariable")
  private static final String UNKNOWN_USER_AGENT = "Unknown";

  // TD-FUT-031: same 7-day rationale as recordaccountlogindevice.RecordAccountLoginDeviceService's
  // own identical constant.
  @SuppressWarnings("PMD.LongVariable")
  private static final Duration NEW_DEVICE_ALERT_TOKEN_TTL = Duration.ofDays(7);

  private final PlatformKnownDeviceRepository knownDevices;
  private final PlatformAccountRepository platformAccounts;
  private final PlatformMailSender mailSender;
  private final AuditEventRecorder auditEvents;
  private final Instant platformKnownDeviceMigrationCutoverAt;
  private final PlatformVerificationTokenRepository verificationTokens;

  @SuppressWarnings("java:S107") // one parameter per collaborating port — same rationale as
  // recordaccountlogindevice.RecordAccountLoginDeviceService's own identical suppression.
  public RecordPlatformAccountLoginDeviceService(
      final PlatformKnownDeviceRepository knownDevices,
      final PlatformAccountRepository platformAccounts,
      final PlatformMailSender mailSender,
      final AuditEventRecorder auditEvents,
      final Instant platformKnownDeviceMigrationCutoverAt,
      final PlatformVerificationTokenRepository verificationTokens) {
    this.knownDevices = knownDevices;
    this.platformAccounts = platformAccounts;
    this.mailSender = mailSender;
    this.auditEvents = auditEvents;
    this.platformKnownDeviceMigrationCutoverAt = platformKnownDeviceMigrationCutoverAt;
    this.verificationTokens = verificationTokens;
  }

  // Same three-genuinely-distinct-outcomes rationale as RecordAccountLoginDeviceService's own
  // identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Override
  public Optional<String> handle(final RecordPlatformAccountLoginDeviceCommand command) {
    final String presentedToken = command.presentedDeviceToken();
    if (presentedToken != null && !presentedToken.isBlank()) {
      final Optional<PlatformKnownDevice> recognized =
          knownDevices.findByPlatformAccountIdAndDeviceTokenHash(
              command.platformAccountId(), RefreshTokenSecret.hash(presentedToken));
      if (recognized.isPresent()) {
        final PlatformKnownDevice device = recognized.get();
        device.touch();
        knownDevices.save(device);
        return Optional.empty();
      }
    }

    final boolean isFirstEverKnownDevice =
        !knownDevices.existsByPlatformAccountId(command.platformAccountId());

    final String rawDeviceToken = RefreshTokenSecret.generateRawValue();
    final PlatformKnownDevice device =
        PlatformKnownDevice.recognize(
            command.platformAccountId(),
            normalizedUserAgent(command.userAgent()),
            RefreshTokenSecret.hash(rawDeviceToken));
    try {
      knownDevices.save(device);
    } catch (final DataIntegrityViolationException e) {
      LOG.warn("event=platform_known_device_token_collision", e);
      return Optional.empty();
    }

    recordAudit(command.platformAccountId(), device.id());

    final PlatformAccount account = findPlatformAccountOrNull(command.platformAccountId());
    if (account != null) {
      final boolean isMigrationArtifact =
          isFirstEverKnownDevice
              && account.createdAt().isBefore(platformKnownDeviceMigrationCutoverAt);
      if (isMigrationArtifact) {
        LOG.info("event=new_platform_device_notification_suppressed_migration_grandfather");
      } else {
        // TD-FUT-031: minted and persisted here, synchronously, before the mail send itself —
        // same "never lose a real, usable token to a rejected/never-run task" reasoning as the
        // tenant-tier sibling's own identical mint call, though this class has no background
        // executor to race against in the first place (the mail send below is already
        // synchronous). A mint failure degrades to a null token (informational-only email) rather
        // than losing the login, same guarantee this class's own Javadoc already establishes.
        final String rawAlertToken = mintNewDeviceAlertTokenOrNull(command.platformAccountId());
        try {
          mailSender.sendNewPlatformDeviceLoginNotification(
              account.email().value(),
              device.userAgent(),
              command.sourceIp(),
              device.firstSeenAt(),
              rawAlertToken);
        } catch (final MailDeliveryException e) {
          // BR-DATA-01: status/event only, never the recipient address or any other PII.
          LOG.warn("event=new_platform_device_notification_failed", e);
        }
      }
    }

    return Optional.of(rawDeviceToken);
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private void recordAudit(final PlatformAccountId platformAccountId, final UUID deviceId) {
    try {
      auditEvents.write(
          AuditActor.platformAccount(platformAccountId.value()),
          "platform_account.new_device_detected",
          "PlatformKnownDevice",
          deviceId.toString(),
          null);
    } catch (final RuntimeException e) {
      LOG.warn("event=platform_account_new_device_detected_audit_write_failed", e);
    }
  }

  // Two genuinely distinct exits (minted / mint failed), same rationale as
  // recordaccountlogindevice.RecordAccountLoginDeviceService's own identical suppression.
  @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
  private String mintNewDeviceAlertTokenOrNull(final PlatformAccountId platformAccountId) {
    try {
      final String rawToken = RefreshTokenSecret.generateRawValue();
      final PlatformVerificationToken token =
          PlatformVerificationToken.issue(
              platformAccountId,
              VerificationTokenType.NEW_DEVICE_LOGIN_ALERT,
              RefreshTokenSecret.hash(rawToken),
              Instant.now().plus(NEW_DEVICE_ALERT_TOKEN_TTL));
      verificationTokens.save(token);
      return rawToken;
    } catch (final RuntimeException e) {
      LOG.warn("event=new_platform_device_alert_token_mint_failed", e);
      return null;
    }
  }

  @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
  private PlatformAccount findPlatformAccountOrNull(final PlatformAccountId platformAccountId) {
    try {
      return platformAccounts.findById(platformAccountId).orElse(null);
    } catch (final RuntimeException e) {
      LOG.warn("event=platform_account_new_device_detected_account_lookup_failed", e);
      return null;
    }
  }

  private static String normalizedUserAgent(final String userAgent) {
    return userAgent == null || userAgent.isBlank() ? UNKNOWN_USER_AGENT : userAgent;
  }
}
