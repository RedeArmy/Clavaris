package com.clavaris.identity.application.usecase.requestemailchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.authenticatewithpassword.PasswordVerifier;
import com.clavaris.identity.application.usecase.changepassword.IncorrectCurrentPasswordException;
import com.clavaris.identity.application.usecase.changepassword.RecentSignInRequiredException;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.EmailChangeRequest;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

class RequestEmailChangeServiceTest {

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

  private AccountRepository accounts;
  private PasswordVerifier verifier;
  private EmailChangeRequestRepository requests;
  private MailSender mail;
  private SelfServiceEmailChangePolicy policy;
  private AuditEventRecorder audit;
  private RequestEmailChangeService service;
  private Account account;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    verifier = mock(PasswordVerifier.class);
    requests = mock(EmailChangeRequestRepository.class);
    mail = mock(MailSender.class);
    policy = mock(SelfServiceEmailChangePolicy.class);
    when(policy.isAllowed(any())).thenReturn(true);
    audit = mock(AuditEventRecorder.class);
    service =
        new RequestEmailChangeService(
            accounts,
            verifier,
            requests,
            mail,
            audit,
            policy,
            TransactionOperations.withoutTransaction());
    account = Account.register(organizationId, new Email("old@example.com"));
    account.attachPasswordCredential("argon2id$hash");
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
    when(verifier.matches("current-secret", "argon2id$hash")).thenReturn(true);
  }

  private RequestEmailChangeCommand command(final String password, final String newEmail) {
    return new RequestEmailChangeCommand(
        account.id(),
        password,
        new Email(newEmail),
        AuditActor.account(account.id().value()),
        false);
  }

  @Test
  void storesAHashedRequestAndMailsBothAddresses() {
    service.handle(command("current-secret", "new@example.com"));

    final ArgumentCaptor<EmailChangeRequest> saved =
        ArgumentCaptor.forClass(EmailChangeRequest.class);
    verify(requests).replaceFor(saved.capture());
    assertThat(saved.getValue().newEmail()).isEqualTo(new Email("new@example.com"));
    final ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
    verify(mail)
        .sendEmailChangeVerification(eq("new@example.com"), eq(organizationId), raw.capture());
    assertThat(saved.getValue().tokenHash()).isNotEqualTo(raw.getValue());
    verify(mail).sendEmailChangeNotice("old@example.com", organizationId, "new@example.com", false);
    assertThat(account.email()).isEqualTo(new Email("old@example.com"));
  }

  @Test
  void aWrongPasswordStoresAndSendsNothing() {
    final RequestEmailChangeCommand attempt = command("wrong", "new@example.com");
    assertThatExceptionOfType(IncorrectCurrentPasswordException.class)
        .isThrownBy(() -> service.handle(attempt));

    verify(requests, never()).replaceFor(any());
    verify(mail, never()).sendEmailChangeVerification(anyString(), any(), anyString());
  }

  @Test
  void anAddressAlreadyInUseLooksLikeSuccessButSendsNothing() {
    when(accounts.existsByOrganizationIdAndEmail(organizationId, new Email("taken@example.com")))
        .thenReturn(true);

    service.handle(command("current-secret", "taken@example.com"));

    verify(requests, never()).replaceFor(any());
    verify(mail, never()).sendEmailChangeVerification(anyString(), any(), anyString());
    verify(mail, never())
        .sendEmailChangeNotice(
            anyString(), any(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  void theSameAddressIsANoOp() {
    service.handle(command("current-secret", "old@example.com"));

    verify(requests, never()).replaceFor(any());
  }

  // TD-FUT-053: an Account with no password proves itself with a sign-in made moments ago.
  @Test
  void anAccountWithoutAPasswordMayChangeItsEmailRightAfterSigningIn() {
    final Account social = Account.register(organizationId, new Email("s@example.com"));
    when(accounts.findById(social.id())).thenReturn(Optional.of(social));

    service.handle(
        new RequestEmailChangeCommand(
            social.id(),
            "",
            new Email("n@example.com"),
            AuditActor.account(social.id().value()),
            true));

    verify(requests).replaceFor(any());
    verify(mail).sendEmailChangeVerification(eq("n@example.com"), eq(organizationId), anyString());
    verify(verifier, never()).matches(any(), any());
  }

  @Test
  void anAccountWithoutAPasswordMustSignInAgainWhenItsSessionIsOld() {
    final Account social = Account.register(organizationId, new Email("s@example.com"));
    when(accounts.findById(social.id())).thenReturn(Optional.of(social));
    final RequestEmailChangeCommand attempt =
        new RequestEmailChangeCommand(
            social.id(),
            "",
            new Email("n@example.com"),
            AuditActor.account(social.id().value()),
            false);

    assertThatExceptionOfType(RecentSignInRequiredException.class)
        .isThrownBy(() -> service.handle(attempt));

    verify(requests, never()).replaceFor(any());
  }

  @Test
  void aRecentSignInDoesNotReplaceThePasswordOfAnAccountThatHasOne() {
    final RequestEmailChangeCommand attempt =
        new RequestEmailChangeCommand(
            account.id(),
            "wrong",
            new Email("new@example.com"),
            AuditActor.account(account.id().value()),
            true);

    assertThatExceptionOfType(IncorrectCurrentPasswordException.class)
        .isThrownBy(() -> service.handle(attempt));

    verify(requests, never()).replaceFor(any());
  }

  // The credential stored at a social sign-up is random; asking for it would lock the person out.
  @Test
  void aGeneratedPasswordIsNotAskedForJustARecentSignIn() {
    final Account social = Account.register(organizationId, new Email("s@example.com"));
    social.attachGeneratedPasswordCredential("argon2id$random");
    when(accounts.findById(social.id())).thenReturn(Optional.of(social));

    service.handle(
        new RequestEmailChangeCommand(
            social.id(),
            "",
            new Email("n@example.com"),
            AuditActor.account(social.id().value()),
            true));

    verify(requests).replaceFor(any());
    verify(verifier, never()).matches(any(), any());
  }

  // TD-FUT-052: an Organization whose application owns the email switches this off.
  @Test
  void anOrganizationThatSwitchedEmailChangeOffRefusesBeforeAskingForAPassword() {
    when(policy.isAllowed(organizationId)).thenReturn(false);
    final RequestEmailChangeCommand attempt = command("current-secret", "new@example.com");

    assertThatExceptionOfType(EmailChangeNotAllowedException.class)
        .isThrownBy(() -> service.handle(attempt));

    verify(verifier, never()).matches(any(), any());
    verify(requests, never()).replaceFor(any());
  }
}
