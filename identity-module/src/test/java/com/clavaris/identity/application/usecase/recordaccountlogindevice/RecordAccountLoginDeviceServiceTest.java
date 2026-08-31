package com.clavaris.identity.application.usecase.recordaccountlogindevice;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.MailDeliveryException;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.KnownDevice;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecordAccountLoginDeviceServiceTest {

  private KnownDeviceRepository knownDevices;
  private AccountRepository accounts;
  private MailSender mailSender;
  private RecordAccountLoginDeviceService service;
  private Account account;

  @BeforeEach
  void setUp() {
    knownDevices = mock(KnownDeviceRepository.class);
    accounts = mock(AccountRepository.class);
    mailSender = mock(MailSender.class);
    service = new RecordAccountLoginDeviceService(knownDevices, accounts, mailSender);

    account =
        Account.register(new OrganizationId(UUID.randomUUID()), new Email("user@example.com"));
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
  }

  @Test
  void aBrandNewDevicePersistsItAndSendsTheNotification() {
    when(knownDevices.findByAccountIdAndUserAgent(account.id(), "Mozilla/5.0"))
        .thenReturn(Optional.empty());

    service.handle(new RecordAccountLoginDeviceCommand(account.id(), "Mozilla/5.0", "1.2.3.4"));

    verify(knownDevices).save(any(KnownDevice.class));
    verify(mailSender)
        .sendNewDeviceLoginNotification(
            eq(account.email().value()),
            eq(account.organizationId()),
            eq("Mozilla/5.0"),
            eq("1.2.3.4"),
            any());
  }

  @Test
  void anAlreadyKnownDeviceTouchesItAndSendsNoNotification() {
    KnownDevice existing = KnownDevice.recognize(account.id(), "Mozilla/5.0");
    when(knownDevices.findByAccountIdAndUserAgent(account.id(), "Mozilla/5.0"))
        .thenReturn(Optional.of(existing));

    service.handle(new RecordAccountLoginDeviceCommand(account.id(), "Mozilla/5.0", "1.2.3.4"));

    verify(knownDevices).save(existing);
    verifyNoInteractions(mailSender);
  }

  @Test
  void aBlankUserAgentIsANoOp() {
    service.handle(new RecordAccountLoginDeviceCommand(account.id(), "", "1.2.3.4"));
    service.handle(new RecordAccountLoginDeviceCommand(account.id(), null, "1.2.3.4"));

    verifyNoInteractions(knownDevices);
    verifyNoInteractions(mailSender);
  }

  // The one case this whole class exists to prove: unlike RequestEmailVerificationService, a
  // failed send here must never propagate and fail the caller's own login request.
  @Test
  void aFailedNotificationEmailNeverPropagatesAndTheDeviceRowIsStillPersisted() {
    when(knownDevices.findByAccountIdAndUserAgent(account.id(), "Mozilla/5.0"))
        .thenReturn(Optional.empty());
    doThrow(new MailDeliveryException("Resend is down"))
        .when(mailSender)
        .sendNewDeviceLoginNotification(any(), any(), any(), any(), any());

    assertThatCode(
            () ->
                service.handle(
                    new RecordAccountLoginDeviceCommand(account.id(), "Mozilla/5.0", "1.2.3.4")))
        .doesNotThrowAnyException();

    verify(knownDevices).save(any(KnownDevice.class));
  }

  @Test
  void anUnresolvableAccountSendsNoNotificationButStillRecordsTheDevice() {
    AccountId unknownAccountId = AccountId.newId();
    when(accounts.findById(unknownAccountId)).thenReturn(Optional.empty());
    when(knownDevices.findByAccountIdAndUserAgent(unknownAccountId, "Mozilla/5.0"))
        .thenReturn(Optional.empty());

    service.handle(new RecordAccountLoginDeviceCommand(unknownAccountId, "Mozilla/5.0", "1.2.3.4"));

    verify(knownDevices).save(any(KnownDevice.class));
    verify(mailSender, never()).sendNewDeviceLoginNotification(any(), any(), any(), any(), any());
  }
}
