package com.clavaris.identity.application.usecase.requestpasswordreset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RequestPasswordResetServiceTest {

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
  private final Email email = new Email("account-holder@example.com");

  private AccountRepository accounts;
  private VerificationTokenRepository tokens;
  private MailSender mailSender;
  private EventOutboxWriter outbox;
  private RequestPasswordResetService service;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    tokens = mock(VerificationTokenRepository.class);
    mailSender = mock(MailSender.class);
    outbox = mock(EventOutboxWriter.class);
    service = new RequestPasswordResetService(accounts, tokens, mailSender, outbox);
  }

  @Test
  void issuesAPasswordResetTokenAndSendsTheEmailForAKnownAccount() {
    Account account = Account.register(organizationId, email);
    account.attachPasswordCredential("existing-hash");
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));

    service.handle(new RequestPasswordResetCommand(organizationId, email));

    ArgumentCaptor<VerificationToken> captor = ArgumentCaptor.forClass(VerificationToken.class);
    verify(tokens).save(captor.capture());
    assertThat(captor.getValue().accountId()).isEqualTo(account.id());
    assertThat(captor.getValue().type()).isEqualTo(VerificationTokenType.PASSWORD_RESET);
    verify(mailSender).sendPasswordReset(eq(email.value()), eq(organizationId), any());
    verify(outbox).write(eq("password_reset.requested"), eq(account.id()), any());
  }

  @Test
  void isANoOpThatDoesNotRevealWhetherTheAccountExists() {
    // BR-ID-05/user-enumeration prevention: no exception, no distinguishable side effect from the
    // caller's perspective when the email doesn't resolve to an account in this Organization.
    when(accounts.findByOrganizationIdAndEmail(organizationId, email)).thenReturn(Optional.empty());

    service.handle(new RequestPasswordResetCommand(organizationId, email));

    verify(tokens, never()).save(any());
    verify(mailSender, never()).sendPasswordReset(any(), any(), any());
    verify(outbox, never()).write(any(), any(), any());
  }
}
