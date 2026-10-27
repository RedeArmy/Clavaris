package com.clavaris.identity.application.usecase.confirmemailchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EmailAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.requestemailchange.EmailChangeRequestRepository;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.EmailChangeRequest;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class ConfirmEmailChangeServiceTest {

  private static final String RAW = "raw-link-token";

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

  private EmailChangeRequestRepository requests;
  private AccountRepository accounts;
  private AuditEventRecorder audit;
  private MailSender mail;
  private ConfirmEmailChangeService service;
  private Account account;
  private EmailChangeRequest request;

  @BeforeEach
  void setUp() {
    requests = mock(EmailChangeRequestRepository.class);
    accounts = mock(AccountRepository.class);
    audit = mock(AuditEventRecorder.class);
    mail = mock(MailSender.class);
    service = new ConfirmEmailChangeService(requests, accounts, audit, mail);
    account = Account.register(organizationId, new Email("old@example.com"));
    request =
        EmailChangeRequest.issue(
            account.id(),
            new Email("new@example.com"),
            RefreshTokenSecret.hash(RAW),
            Instant.now().plusSeconds(600));
    when(requests.findByTokenHash(RefreshTokenSecret.hash(RAW))).thenReturn(Optional.of(request));
    when(requests.consumeIfActive(any(), any())).thenReturn(true);
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
  }

  @Test
  void movesTheAddressVerifiedAndTellsTheOldOne() {
    service.handle(new ConfirmEmailChangeCommand(account.id(), RAW));

    assertThat(account.email()).isEqualTo(new Email("new@example.com"));
    assertThat(account.emailVerifiedAt()).isPresent();
    verify(accounts).save(account);
    verify(mail).sendEmailChangeNotice("old@example.com", organizationId, "new@example.com", true);
  }

  @Test
  void anUnknownLinkIsInvalid() {
    assertThatExceptionOfType(InvalidEmailChangeTokenException.class)
        .isThrownBy(() -> service.handle(new ConfirmEmailChangeCommand(account.id(), "nope")));
  }

  @Test
  void aLinkLostToAConcurrentUseIsInvalid() {
    when(requests.consumeIfActive(any(), any())).thenReturn(false);

    assertThatExceptionOfType(InvalidEmailChangeTokenException.class)
        .isThrownBy(() -> service.handle(new ConfirmEmailChangeCommand(account.id(), RAW)));
    verify(accounts, never()).save(any());
  }

  @Test
  void aLinkOnlyWorksForTheAccountThatAskedForIt() {
    final AccountId someoneElse = AccountId.newId();

    assertThatExceptionOfType(InvalidEmailChangeTokenException.class)
        .isThrownBy(() -> service.handle(new ConfirmEmailChangeCommand(someoneElse, RAW)));
    verify(requests, never()).consumeIfActive(any(), any());
  }

  @Test
  void anAddressTakenInTheMeantimeIsRefused() {
    when(accounts.existsByOrganizationIdAndEmail(organizationId, new Email("new@example.com")))
        .thenReturn(true);

    assertThatExceptionOfType(EmailAlreadyRegisteredException.class)
        .isThrownBy(() -> service.handle(new ConfirmEmailChangeCommand(account.id(), RAW)));
    assertThat(account.email()).isEqualTo(new Email("old@example.com"));
  }

  @Test
  void losingTheUniqueConstraintRaceIsRefusedToo() {
    doThrow(new DataIntegrityViolationException("dup")).when(accounts).save(account);

    assertThatExceptionOfType(EmailAlreadyRegisteredException.class)
        .isThrownBy(() -> service.handle(new ConfirmEmailChangeCommand(account.id(), RAW)));
  }
}
