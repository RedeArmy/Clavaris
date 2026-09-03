package com.clavaris.identity.application.usecase.recordplatformaccountlogindevice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.MailDeliveryException;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.PlatformMailSender;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.domain.model.PlatformKnownDevice;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** TD-FUT-026: platform-tier mirror of {@code RecordAccountLoginDeviceServiceTest}. */
class RecordPlatformAccountLoginDeviceServiceTest {

  // Deliberately safely in the past relative to any real test-execution wall clock — unlike the
  // real production default (PlatformAccountUseCaseConfig's own, pinned to this feature's actual
  // migration timestamp), this constant only needs to prove the mechanism, not model a real
  // deploy moment; a cutover close to "now" would make PlatformAccount.register()'s own
  // Instant.now() createdAt flakily land on either side of it depending on when the test runs.
  private static final Instant MIGRATION_CUTOVER_AT = Instant.parse("2026-01-01T00:00:00Z");

  private PlatformKnownDeviceRepository knownDevices;
  private PlatformAccountRepository platformAccounts;
  private PlatformMailSender mailSender;
  private AuditEventRecorder auditEvents;
  private RecordPlatformAccountLoginDeviceService service;
  private PlatformAccount account;

  @BeforeEach
  void setUp() {
    knownDevices = mock(PlatformKnownDeviceRepository.class);
    platformAccounts = mock(PlatformAccountRepository.class);
    mailSender = mock(PlatformMailSender.class);
    auditEvents = mock(AuditEventRecorder.class);
    service =
        new RecordPlatformAccountLoginDeviceService(
            knownDevices, platformAccounts, mailSender, auditEvents, MIGRATION_CUTOVER_AT);

    account = PlatformAccount.register(new Email("operator@example.com"));
    when(platformAccounts.findById(account.id())).thenReturn(Optional.of(account));
  }

  @Test
  void aRecognizedDeviceCookieTouchesTheRowAndReturnsNoNewToken() {
    PlatformKnownDevice existing =
        PlatformKnownDevice.recognize(account.id(), "Mozilla/5.0", "existing-hash");
    when(knownDevices.findByPlatformAccountIdAndDeviceTokenHash(
            account.id(), RefreshTokenSecret.hash("the-raw-cookie-value")))
        .thenReturn(Optional.of(existing));

    Optional<String> result =
        service.handle(
            new RecordPlatformAccountLoginDeviceCommand(
                account.id(), "Mozilla/5.0", "1.2.3.4", "the-raw-cookie-value"));

    assertThat(result).isEmpty();
    verify(knownDevices).save(existing);
    verifyNoInteractions(mailSender);
    verifyNoInteractions(auditEvents);
  }

  @Test
  void noPresentedCookieMintsPersistsNotifiesAndAuditsAndReturnsANewToken() {
    Optional<String> result =
        service.handle(
            new RecordPlatformAccountLoginDeviceCommand(
                account.id(), "Mozilla/5.0", "1.2.3.4", null));

    assertThat(result).isPresent();
    verify(knownDevices).save(any(PlatformKnownDevice.class));
    verify(mailSender)
        .sendNewPlatformDeviceLoginNotification(
            eq(account.email().value()), eq("Mozilla/5.0"), eq("1.2.3.4"), any());
    verify(auditEvents)
        .write(
            eq(AuditActor.platformAccount(account.id().value())),
            eq("platform_account.new_device_detected"),
            eq("PlatformKnownDevice"),
            any(),
            isNull());
  }

  @Test
  void anUnrecognizedPresentedCookieFallsThroughToTheNewDevicePath() {
    when(knownDevices.findByPlatformAccountIdAndDeviceTokenHash(eq(account.id()), any()))
        .thenReturn(Optional.empty());

    Optional<String> result =
        service.handle(
            new RecordPlatformAccountLoginDeviceCommand(
                account.id(), "Mozilla/5.0", "1.2.3.4", "an-unrecognized-cookie-value"));

    assertThat(result).isPresent();
    verify(mailSender).sendNewPlatformDeviceLoginNotification(any(), any(), any(), any());
  }

  @Test
  void aBlankOrMissingUserAgentIsStillARealDeviceWorthTracking() {
    Optional<String> blankResult =
        service.handle(
            new RecordPlatformAccountLoginDeviceCommand(account.id(), "", "1.2.3.4", null));
    Optional<String> nullResult =
        service.handle(
            new RecordPlatformAccountLoginDeviceCommand(account.id(), null, "1.2.3.4", null));

    assertThat(blankResult).isPresent();
    assertThat(nullResult).isPresent();
    verify(knownDevices, times(2)).save(any(PlatformKnownDevice.class));
  }

  @Test
  void aFailedNotificationEmailNeverPropagatesAndTheDeviceRowIsStillWritten() {
    doThrow(new MailDeliveryException("Resend is down"))
        .when(mailSender)
        .sendNewPlatformDeviceLoginNotification(any(), any(), any(), any());

    Optional<String> result =
        service.handle(
            new RecordPlatformAccountLoginDeviceCommand(
                account.id(), "Mozilla/5.0", "1.2.3.4", null));

    assertThat(result).isPresent();
    verify(knownDevices).save(any(PlatformKnownDevice.class));
  }

  @Test
  void anAuditWriteFailureNeverPropagatesAndMailIsStillAttempted() {
    doThrow(new RuntimeException("Postgres hiccup"))
        .when(auditEvents)
        .write(any(), any(), any(), any(), any());

    Optional<String> result =
        service.handle(
            new RecordPlatformAccountLoginDeviceCommand(
                account.id(), "Mozilla/5.0", "1.2.3.4", null));

    assertThat(result).isPresent();
    verify(knownDevices).save(any(PlatformKnownDevice.class));
    verify(mailSender)
        .sendNewPlatformDeviceLoginNotification(
            eq(account.email().value()), eq("Mozilla/5.0"), eq("1.2.3.4"), any());
  }

  @Test
  void anAccountLookupFailureNeverPropagatesAndDegradesLikeAnUnresolvedAccount() {
    when(platformAccounts.findById(account.id()))
        .thenThrow(new RuntimeException("Postgres hiccup"));

    Optional<String> result =
        service.handle(
            new RecordPlatformAccountLoginDeviceCommand(
                account.id(), "Mozilla/5.0", "1.2.3.4", null));

    assertThat(result).isPresent();
    verify(knownDevices).save(any(PlatformKnownDevice.class));
    verify(mailSender, never()).sendNewPlatformDeviceLoginNotification(any(), any(), any(), any());
    verify(auditEvents)
        .write(any(), eq("platform_account.new_device_detected"), any(), any(), isNull());
  }

  @Test
  void aTokenCollisionOnSaveDegradesToNoNotificationInsteadOfPropagating() {
    doThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"))
        .when(knownDevices)
        .save(any(PlatformKnownDevice.class));

    Optional<String> result =
        service.handle(
            new RecordPlatformAccountLoginDeviceCommand(
                account.id(), "Mozilla/5.0", "1.2.3.4", null));

    assertThat(result).isEmpty();
    verifyNoInteractions(mailSender);
    verifyNoInteractions(auditEvents);
  }

  // Same migration-grandfather regression this row's own service Javadoc names — every
  // PlatformAccount that already existed before platform_known_devices went live must not read as
  // a mass-compromise alert on its very next login.
  @Test
  void aPreExistingPlatformAccountsFirstPostMigrationDeviceSuppressesNotification() {
    PlatformAccount preExisting =
        PlatformAccount.reconstitute(
            PlatformAccountId.newId(),
            new Email("long-time-operator@example.com"),
            MIGRATION_CUTOVER_AT.minusSeconds(3600),
            null,
            AccountStatus.ACTIVE,
            null);
    when(platformAccounts.findById(preExisting.id())).thenReturn(Optional.of(preExisting));
    when(knownDevices.existsByPlatformAccountId(preExisting.id())).thenReturn(false);

    Optional<String> result =
        service.handle(
            new RecordPlatformAccountLoginDeviceCommand(
                preExisting.id(), "Mozilla/5.0", "1.2.3.4", null));

    assertThat(result).isPresent();
    verify(knownDevices).save(any(PlatformKnownDevice.class));
    verify(auditEvents)
        .write(
            eq(AuditActor.platformAccount(preExisting.id().value())),
            eq("platform_account.new_device_detected"),
            eq("PlatformKnownDevice"),
            any(),
            isNull());
    verifyNoInteractions(mailSender);
  }

  @Test
  void aPreExistingPlatformAccountsSecondDeviceAfterTheMigrationStillNotifiesNormally() {
    PlatformAccount preExisting =
        PlatformAccount.reconstitute(
            PlatformAccountId.newId(),
            new Email("long-time-operator@example.com"),
            MIGRATION_CUTOVER_AT.minusSeconds(3600),
            null,
            AccountStatus.ACTIVE,
            null);
    when(platformAccounts.findById(preExisting.id())).thenReturn(Optional.of(preExisting));
    when(knownDevices.existsByPlatformAccountId(preExisting.id())).thenReturn(true);

    Optional<String> result =
        service.handle(
            new RecordPlatformAccountLoginDeviceCommand(
                preExisting.id(), "Mozilla/5.0", "1.2.3.4", null));

    assertThat(result).isPresent();
    verify(mailSender)
        .sendNewPlatformDeviceLoginNotification(
            eq(preExisting.email().value()), any(), any(), any());
  }

  @Test
  void aBrandNewPlatformAccountCreatedAfterTheCutoverStillNotifiesOnItsFirstDevice() {
    PlatformAccount brandNew =
        PlatformAccount.reconstitute(
            PlatformAccountId.newId(),
            new Email("new-operator@example.com"),
            MIGRATION_CUTOVER_AT.plusSeconds(3600),
            null,
            AccountStatus.ACTIVE,
            null);
    when(platformAccounts.findById(brandNew.id())).thenReturn(Optional.of(brandNew));
    when(knownDevices.existsByPlatformAccountId(brandNew.id())).thenReturn(false);

    Optional<String> result =
        service.handle(
            new RecordPlatformAccountLoginDeviceCommand(
                brandNew.id(), "Mozilla/5.0", "1.2.3.4", null));

    assertThat(result).isPresent();
    verify(mailSender)
        .sendNewPlatformDeviceLoginNotification(eq(brandNew.email().value()), any(), any(), any());
  }

  @Test
  void anUnresolvablePlatformAccountStillRecordsAndAuditsTheDeviceButSkipsTheNotification() {
    PlatformAccountId unknownId = PlatformAccountId.newId();
    when(platformAccounts.findById(unknownId)).thenReturn(Optional.empty());

    Optional<String> result =
        service.handle(
            new RecordPlatformAccountLoginDeviceCommand(unknownId, "Mozilla/5.0", "1.2.3.4", null));

    assertThat(result).isPresent();
    verify(knownDevices).save(any(PlatformKnownDevice.class));
    verify(mailSender, never()).sendNewPlatformDeviceLoginNotification(any(), any(), any(), any());
    verify(auditEvents)
        .write(any(), eq("platform_account.new_device_detected"), any(), any(), isNull());
  }
}
