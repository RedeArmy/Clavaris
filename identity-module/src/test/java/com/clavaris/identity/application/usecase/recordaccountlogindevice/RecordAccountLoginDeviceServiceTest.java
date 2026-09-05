package com.clavaris.identity.application.usecase.recordaccountlogindevice;

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
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.requestemailverification.MailDeliveryException;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.domain.event.AccountNewDeviceDetectedEvent;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.KnownDevice;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class RecordAccountLoginDeviceServiceTest {

  private static final Instant MIGRATION_CUTOVER_AT = Instant.parse("2026-08-31T10:00:00Z");

  private KnownDeviceRepository knownDevices;
  private AccountRepository accounts;
  private MailSender mailSender;
  private AuditEventRecorder auditEvents;
  private EventOutboxWriter outbox;
  private RecordAccountLoginDeviceService service;
  private Account account;

  @BeforeEach
  void setUp() {
    knownDevices = mock(KnownDeviceRepository.class);
    accounts = mock(AccountRepository.class);
    mailSender = mock(MailSender.class);
    auditEvents = mock(AuditEventRecorder.class);
    outbox = mock(EventOutboxWriter.class);
    service =
        new RecordAccountLoginDeviceService(
            knownDevices, accounts, mailSender, auditEvents, outbox, MIGRATION_CUTOVER_AT);

    // Registered "now" (well after the cutover) — every existing test in this class exercises
    // ordinary, non-grandfathered behavior; the migration-suppression tests below build their own
    // pre-cutover Account explicitly instead of relying on this one.
    account =
        Account.register(new OrganizationId(UUID.randomUUID()), new Email("user@example.com"));
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
  }

  @Test
  void aRecognizedDeviceCookieTouchesTheRowAndReturnsNoNewToken() {
    KnownDevice existing = KnownDevice.recognize(account.id(), "Mozilla/5.0", "existing-hash");
    when(knownDevices.findByAccountIdAndDeviceTokenHash(
            account.id(), RefreshTokenSecret.hash("the-raw-cookie-value")))
        .thenReturn(Optional.of(existing));

    Optional<String> result =
        service.handle(
            new RecordAccountLoginDeviceCommand(
                account.id(), "Mozilla/5.0", "1.2.3.4", "the-raw-cookie-value"));

    assertThat(result).isEmpty();
    verify(knownDevices).save(existing);
    verifyNoInteractions(mailSender);
    verifyNoInteractions(auditEvents);
    verifyNoInteractions(outbox);
  }

  @Test
  void noPresentedCookieMintsPersistsNotifiesAuditsPublishesAndReturnsANewToken() {
    Optional<String> result =
        service.handle(
            new RecordAccountLoginDeviceCommand(account.id(), "Mozilla/5.0", "1.2.3.4", null));

    assertThat(result).isPresent();
    verify(knownDevices).save(any(KnownDevice.class));
    verify(mailSender)
        .sendNewDeviceLoginNotification(
            eq(account.email().value()),
            eq(account.organizationId()),
            eq("Mozilla/5.0"),
            eq("1.2.3.4"),
            any());
    verify(auditEvents)
        .write(
            eq(AuditActor.account(account.id().value())),
            eq("account.new_device_detected"),
            eq("KnownDevice"),
            any(),
            isNull());
    verify(outbox)
        .write(
            eq("account.new_device_detected"),
            eq(account.id()),
            any(),
            any(AccountNewDeviceDetectedEvent.class));
  }

  @Test
  void anUnrecognizedPresentedCookieFallsThroughToTheNewDevicePath() {
    // Stale, tampered, foreign, or an already-purged row — same outcome as no cookie at all.
    when(knownDevices.findByAccountIdAndDeviceTokenHash(eq(account.id()), any()))
        .thenReturn(Optional.empty());

    Optional<String> result =
        service.handle(
            new RecordAccountLoginDeviceCommand(
                account.id(), "Mozilla/5.0", "1.2.3.4", "an-unrecognized-cookie-value"));

    assertThat(result).isPresent();
    verify(mailSender).sendNewDeviceLoginNotification(any(), any(), any(), any(), any());
    verify(outbox).write(eq("account.new_device_detected"), eq(account.id()), any(), any());
  }

  @Test
  void aBlankOrMissingUserAgentIsNoLongerANoOpUnderTheCookieBasedDesign() {
    // TD-SEC-033: unlike the old User-Agent-keyed version of this class, a client that sends no
    // User-Agent at all is still a real device worth tracking — the security property no longer
    // depends on that header.
    Optional<String> blankResult =
        service.handle(new RecordAccountLoginDeviceCommand(account.id(), "", "1.2.3.4", null));
    Optional<String> nullResult =
        service.handle(new RecordAccountLoginDeviceCommand(account.id(), null, "1.2.3.4", null));

    assertThat(blankResult).isPresent();
    assertThat(nullResult).isPresent();
    verify(knownDevices, times(2)).save(any(KnownDevice.class));
    verify(outbox, times(2))
        .write(eq("account.new_device_detected"), eq(account.id()), any(), any());
  }

  // The one case this whole class exists to prove: unlike RequestEmailVerificationService, a
  // failed send here must never propagate and fail the caller's own login request. The outbox
  // write happens before the send attempt, so it must still land even when the send itself fails.
  @Test
  void aFailedNotificationEmailNeverPropagatesAndTheDeviceRowAndOutboxEventAreStillWritten() {
    doThrow(new MailDeliveryException("Resend is down"))
        .when(mailSender)
        .sendNewDeviceLoginNotification(any(), any(), any(), any(), any());

    Optional<String> result =
        service.handle(
            new RecordAccountLoginDeviceCommand(account.id(), "Mozilla/5.0", "1.2.3.4", null));

    assertThat(result).isPresent();
    verify(knownDevices).save(any(KnownDevice.class));
    verify(outbox).write(eq("account.new_device_detected"), eq(account.id()), any(), any());
  }

  // TD-SEC-036 (SDE-III review, self-caught same day): the very case the first version of this
  // follow-up got wrong — a transient outbox failure must never surface past this method, since it
  // would have broken this class's own headline "never lets a mail failure propagate" guarantee
  // for a side-channel write that isn't even the mail send. The mail send itself must still be
  // attempted afterward, unaffected by the outbox write's own failure.
  @Test
  void anOutboxWriteFailureNeverPropagatesAndTheMailSendIsStillAttempted() {
    doThrow(new RuntimeException("Postgres hiccup")).when(outbox).write(any(), any(), any(), any());

    Optional<String> result =
        service.handle(
            new RecordAccountLoginDeviceCommand(account.id(), "Mozilla/5.0", "1.2.3.4", null));

    assertThat(result).isPresent();
    verify(knownDevices).save(any(KnownDevice.class));
    verify(mailSender)
        .sendNewDeviceLoginNotification(
            eq(account.email().value()),
            eq(account.organizationId()),
            eq("Mozilla/5.0"),
            eq("1.2.3.4"),
            any());
  }

  // Code review (2026-09-01): the gap TD-SEC-036's own fix left one call too narrow — an audit
  // failure must never propagate either. Outbox and mail must still be attempted afterward,
  // unaffected by the audit write's own failure.
  @Test
  void anAuditWriteFailureNeverPropagatesAndOutboxAndMailAreStillAttempted() {
    doThrow(new RuntimeException("Postgres hiccup"))
        .when(auditEvents)
        .write(any(), any(), any(), any(), any());

    Optional<String> result =
        service.handle(
            new RecordAccountLoginDeviceCommand(account.id(), "Mozilla/5.0", "1.2.3.4", null));

    assertThat(result).isPresent();
    verify(knownDevices).save(any(KnownDevice.class));
    verify(outbox).write(eq("account.new_device_detected"), eq(account.id()), any(), any());
    verify(mailSender)
        .sendNewDeviceLoginNotification(
            eq(account.email().value()),
            eq(account.organizationId()),
            eq("Mozilla/5.0"),
            eq("1.2.3.4"),
            any());
  }

  // Code review (2026-09-01): same gap, one call earlier still — the accounts.findById lookup was
  // also bare. A transient failure here must degrade the same way an already-unresolved account
  // does (device still recorded and audited, outbox/mail skipped), not propagate.
  @Test
  void anAccountLookupFailureNeverPropagatesAndDegradesLikeAnUnresolvedAccount() {
    when(accounts.findById(account.id())).thenThrow(new RuntimeException("Postgres hiccup"));

    Optional<String> result =
        service.handle(
            new RecordAccountLoginDeviceCommand(account.id(), "Mozilla/5.0", "1.2.3.4", null));

    assertThat(result).isPresent();
    verify(knownDevices).save(any(KnownDevice.class));
    verify(mailSender, never()).sendNewDeviceLoginNotification(any(), any(), any(), any(), any());
    verify(auditEvents).write(any(), eq("account.new_device_detected"), any(), any(), isNull());
    verifyNoInteractions(outbox);
  }

  // P1/P2 (SDE-III review, found live) → superseded by TD-SEC-033: the old UA-keyed TOCTOU race
  // (two concurrent logins racing into the same (accountId, userAgent) insert) can no longer
  // happen the same way — each request now mints its own independent random token, so there is
  // no shared key to collide on. What remains is a purely defensive catch for a statistically
  // negligible token collision; this test proves that path degrades gracefully rather than
  // proving a realistic race (see RecordAccountLoginDeviceService's own Javadoc).
  @Test
  void aTokenCollisionOnSaveDegradesToNoNotificationInsteadOfPropagating() {
    doThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"))
        .when(knownDevices)
        .save(any(KnownDevice.class));

    Optional<String> result =
        service.handle(
            new RecordAccountLoginDeviceCommand(account.id(), "Mozilla/5.0", "1.2.3.4", null));

    assertThat(result).isEmpty();
    verifyNoInteractions(mailSender);
    verifyNoInteractions(auditEvents);
    verifyNoInteractions(outbox);
  }

  // Code review finding (2026-09-01): the actual regression this migration grandfather exists to
  // prevent — a pre-existing Account's very first row after the DeviceCookie migration must not
  // read as a "new device" alarm to a user who has been logging in from the same browser for
  // months. The row is still persisted and audited (real, useful bookkeeping); only the
  // outward-facing outbox event and email are suppressed, and only this once.
  @Test
  void
      aPreExistingAccountsFirstPostMigrationDeviceSuppressesNotificationButStillRecordsTheDevice() {
    Account preExistingAccount =
        Account.reconstitute(
            AccountId.newId(),
            new OrganizationId(UUID.randomUUID()),
            new Email("long-time-user@example.com"),
            MIGRATION_CUTOVER_AT.minusSeconds(3600), // created an hour before the migration
            null,
            AccountStatus.ACTIVE,
            null,
            null);
    when(accounts.findById(preExistingAccount.id())).thenReturn(Optional.of(preExistingAccount));
    when(knownDevices.existsByAccountId(preExistingAccount.id())).thenReturn(false);

    Optional<String> result =
        service.handle(
            new RecordAccountLoginDeviceCommand(
                preExistingAccount.id(), "Mozilla/5.0", "1.2.3.4", null));

    assertThat(result).isPresent();
    verify(knownDevices).save(any(KnownDevice.class));
    verify(auditEvents)
        .write(
            eq(AuditActor.account(preExistingAccount.id().value())),
            eq("account.new_device_detected"),
            eq("KnownDevice"),
            any(),
            isNull());
    verifyNoInteractions(mailSender);
    verifyNoInteractions(outbox);
  }

  // The grandfather suppression must never apply more than once: a pre-existing Account's own
  // second (genuinely new) device, discovered after the migration already recorded its first one,
  // is exactly as notification-worthy as it always was.
  @Test
  void aPreExistingAccountsSecondDeviceAfterTheMigrationStillNotifiesNormally() {
    Account preExistingAccount =
        Account.reconstitute(
            AccountId.newId(),
            new OrganizationId(UUID.randomUUID()),
            new Email("long-time-user@example.com"),
            MIGRATION_CUTOVER_AT.minusSeconds(3600),
            null,
            AccountStatus.ACTIVE,
            null,
            null);
    when(accounts.findById(preExistingAccount.id())).thenReturn(Optional.of(preExistingAccount));
    // Already has a row from an earlier, already-suppressed migration-artifact login.
    when(knownDevices.existsByAccountId(preExistingAccount.id())).thenReturn(true);

    Optional<String> result =
        service.handle(
            new RecordAccountLoginDeviceCommand(
                preExistingAccount.id(), "Mozilla/5.0", "1.2.3.4", null));

    assertThat(result).isPresent();
    verify(mailSender)
        .sendNewDeviceLoginNotification(
            eq(preExistingAccount.email().value()), any(), any(), any(), any());
    verify(outbox)
        .write(eq("account.new_device_detected"), eq(preExistingAccount.id()), any(), any());
  }

  // A brand-new signup (created after the cutover) has no migration artifact to suppress — its
  // first-ever device is real news, same as this class's own pre-existing behavior.
  @Test
  void aBrandNewAccountCreatedAfterTheCutoverStillNotifiesOnItsFirstDevice() {
    Account brandNewAccount =
        Account.reconstitute(
            AccountId.newId(),
            new OrganizationId(UUID.randomUUID()),
            new Email("new-signup@example.com"),
            MIGRATION_CUTOVER_AT.plusSeconds(3600), // registered an hour after the migration
            null,
            AccountStatus.ACTIVE,
            null,
            null);
    when(accounts.findById(brandNewAccount.id())).thenReturn(Optional.of(brandNewAccount));
    when(knownDevices.existsByAccountId(brandNewAccount.id())).thenReturn(false);

    Optional<String> result =
        service.handle(
            new RecordAccountLoginDeviceCommand(
                brandNewAccount.id(), "Mozilla/5.0", "1.2.3.4", null));

    assertThat(result).isPresent();
    verify(mailSender)
        .sendNewDeviceLoginNotification(
            eq(brandNewAccount.email().value()), any(), any(), any(), any());
    verify(outbox).write(eq("account.new_device_detected"), eq(brandNewAccount.id()), any(), any());
  }

  @Test
  void anUnresolvableAccountStillRecordsAndAuditsTheDeviceButSkipsTheNotificationAndOutboxEvent() {
    AccountId unknownAccountId = AccountId.newId();
    when(accounts.findById(unknownAccountId)).thenReturn(Optional.empty());

    Optional<String> result =
        service.handle(
            new RecordAccountLoginDeviceCommand(unknownAccountId, "Mozilla/5.0", "1.2.3.4", null));

    assertThat(result).isPresent();
    verify(knownDevices).save(any(KnownDevice.class));
    verify(mailSender, never()).sendNewDeviceLoginNotification(any(), any(), any(), any(), any());
    verify(auditEvents).write(any(), eq("account.new_device_detected"), any(), any(), isNull());
    verifyNoInteractions(outbox);
  }
}
