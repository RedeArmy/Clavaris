package com.clavaris.identity.application.usecase.confirmemailchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.PendingSocialLinkRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EmailAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.requestemailchange.EmailChangeRequestRepository;
import com.clavaris.identity.application.usecase.requestemailchange.SelfServiceEmailChangePolicy;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.domain.event.AccountEmailChangedEvent;
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
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionOperations;

class ConfirmEmailChangeServiceTest {

  private static final String RAW = "raw-link-token";

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

  private EmailChangeRequestRepository requests;
  private AccountRepository accounts;
  private AuditEventRecorder audit;
  private MailSender mail;
  private SelfServiceEmailChangePolicy policy;
  private VerificationTokenRepository verificationTokens;
  private PendingSocialLinkRepository pendingLinks;
  private EventOutboxWriter outbox;
  private ConfirmEmailChangeService service;
  private Account account;
  private EmailChangeRequest request;

  @BeforeEach
  void setUp() {
    requests = mock(EmailChangeRequestRepository.class);
    accounts = mock(AccountRepository.class);
    audit = mock(AuditEventRecorder.class);
    mail = mock(MailSender.class);
    policy = mock(SelfServiceEmailChangePolicy.class);
    when(policy.isAllowed(any())).thenReturn(true);
    verificationTokens = mock(VerificationTokenRepository.class);
    pendingLinks = mock(PendingSocialLinkRepository.class);
    outbox = mock(EventOutboxWriter.class);
    service =
        new ConfirmEmailChangeService(
            requests,
            accounts,
            verificationTokens,
            pendingLinks,
            outbox,
            audit,
            mail,
            policy,
            TransactionOperations.withoutTransaction());
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

  // BR-ID-04: a reset or social-link confirmation mailed to the old address must not outlive the
  // change, or whoever still reads that mailbox could finish it.
  @Test
  void endsEveryTokenAndPendingLinkThatWasMailedToTheOldAddress() {
    service.handle(new ConfirmEmailChangeCommand(account.id(), RAW));

    verify(verificationTokens).invalidateAllActiveForAccount(eq(account.id()), any());
    verify(pendingLinks).invalidateAllActiveForAccount(eq(account.id()), any());
  }

  @Test
  void tellsWebhookConsumersWithBothAddresses() {
    service.handle(new ConfirmEmailChangeCommand(account.id(), RAW));

    ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
    verify(outbox)
        .write(
            eq("account.email_changed"), eq(account.id()), eq(organizationId), payload.capture());
    AccountEmailChangedEvent event = (AccountEmailChangedEvent) payload.getValue();
    assertThat(event.previousEmail()).isEqualTo("old@example.com");
    assertThat(event.email()).isEqualTo("new@example.com");
  }

  @Test
  void aRefusedChangeEndsNothingAndTellsNobody() {
    when(accounts.existsByOrganizationIdAndEmail(organizationId, new Email("new@example.com")))
        .thenReturn(true);
    final ConfirmEmailChangeCommand attempt = new ConfirmEmailChangeCommand(account.id(), RAW);

    assertThatExceptionOfType(EmailAlreadyRegisteredException.class)
        .isThrownBy(() -> service.handle(attempt));

    verify(verificationTokens, never()).invalidateAllActiveForAccount(any(), any());
    verify(outbox, never()).write(any(), any(), any(), any());
  }

  @Test
  void anUnknownLinkIsInvalid() {
    final ConfirmEmailChangeCommand attempt = new ConfirmEmailChangeCommand(account.id(), "nope");
    assertThatExceptionOfType(InvalidEmailChangeTokenException.class)
        .isThrownBy(() -> service.handle(attempt));
  }

  @Test
  void aLinkLostToAConcurrentUseIsInvalid() {
    when(requests.consumeIfActive(any(), any())).thenReturn(false);

    final ConfirmEmailChangeCommand attempt = new ConfirmEmailChangeCommand(account.id(), RAW);
    assertThatExceptionOfType(InvalidEmailChangeTokenException.class)
        .isThrownBy(() -> service.handle(attempt));
    verify(accounts, never()).save(any());
  }

  @Test
  void aLinkOnlyWorksForTheAccountThatAskedForIt() {
    final AccountId someoneElse = AccountId.newId();

    final ConfirmEmailChangeCommand attempt = new ConfirmEmailChangeCommand(someoneElse, RAW);
    assertThatExceptionOfType(InvalidEmailChangeTokenException.class)
        .isThrownBy(() -> service.handle(attempt));
    verify(requests, never()).consumeIfActive(any(), any());
  }

  @Test
  void anAddressTakenInTheMeantimeIsRefused() {
    when(accounts.existsByOrganizationIdAndEmail(organizationId, new Email("new@example.com")))
        .thenReturn(true);

    final ConfirmEmailChangeCommand attempt = new ConfirmEmailChangeCommand(account.id(), RAW);
    assertThatExceptionOfType(EmailAlreadyRegisteredException.class)
        .isThrownBy(() -> service.handle(attempt));
    assertThat(account.email()).isEqualTo(new Email("old@example.com"));
  }

  @Test
  void losingTheUniqueConstraintRaceIsRefusedToo() {
    doThrow(new DataIntegrityViolationException("dup")).when(accounts).save(account);

    final ConfirmEmailChangeCommand attempt = new ConfirmEmailChangeCommand(account.id(), RAW);
    assertThatExceptionOfType(EmailAlreadyRegisteredException.class)
        .isThrownBy(() -> service.handle(attempt));
  }

  // A link requested before the Organization switched the feature off must not complete after.
  @Test
  void aLinkRequestedBeforeTheFeatureWasSwitchedOffNoLongerCompletes() {
    when(policy.isAllowed(organizationId)).thenReturn(false);
    final ConfirmEmailChangeCommand attempt = new ConfirmEmailChangeCommand(account.id(), RAW);

    assertThatExceptionOfType(InvalidEmailChangeTokenException.class)
        .isThrownBy(() -> service.handle(attempt));

    assertThat(account.email()).isEqualTo(new Email("old@example.com"));
    verify(outbox, never()).write(any(), any(), any(), any());
  }
}
