package com.clavaris.identity.application.usecase.recordplatformaccountlogindevice;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.MailDeliveryException;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.PlatformMailSender;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.domain.model.PlatformKnownDevice;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
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
 */
@SuppressWarnings("PMD.LongVariable")
public class RecordPlatformAccountLoginDeviceService
    implements RecordPlatformAccountLoginDeviceUseCase {

  private static final Logger LOG =
      LoggerFactory.getLogger(RecordPlatformAccountLoginDeviceService.class);

  @SuppressWarnings("PMD.LongVariable")
  private static final String UNKNOWN_USER_AGENT = "Unknown";

  private final PlatformKnownDeviceRepository knownDevices;
  private final PlatformAccountRepository platformAccounts;
  private final PlatformMailSender mailSender;
  private final AuditEventRecorder auditEvents;
  private final Instant platformKnownDeviceMigrationCutoverAt;

  public RecordPlatformAccountLoginDeviceService(
      final PlatformKnownDeviceRepository knownDevices,
      final PlatformAccountRepository platformAccounts,
      final PlatformMailSender mailSender,
      final AuditEventRecorder auditEvents,
      final Instant platformKnownDeviceMigrationCutoverAt) {
    this.knownDevices = knownDevices;
    this.platformAccounts = platformAccounts;
    this.mailSender = mailSender;
    this.auditEvents = auditEvents;
    this.platformKnownDeviceMigrationCutoverAt = platformKnownDeviceMigrationCutoverAt;
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
        try {
          mailSender.sendNewPlatformDeviceLoginNotification(
              account.email().value(),
              device.userAgent(),
              command.sourceIp(),
              device.firstSeenAt());
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
