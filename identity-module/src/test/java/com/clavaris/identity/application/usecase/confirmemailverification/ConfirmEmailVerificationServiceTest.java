package com.clavaris.identity.application.usecase.confirmemailverification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConfirmEmailVerificationServiceTest {

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

  private VerificationTokenRepository tokens;
  private AccountRepository accounts;
  private EventOutboxWriter outbox;
  private ConfirmEmailVerificationService service;

  @BeforeEach
  void setUp() {
    tokens = mock(VerificationTokenRepository.class);
    accounts = mock(AccountRepository.class);
    outbox = mock(EventOutboxWriter.class);
    service = new ConfirmEmailVerificationService(tokens, accounts, outbox);
  }

  @Test
  void consumesAnActiveTokenAndMarksTheAccountVerified() {
    Account account = Account.register(organizationId, new Email("new-user@example.com"));
    String rawToken = "a-valid-token-value";
    VerificationToken token =
        VerificationToken.issue(
            account.id(),
            VerificationTokenType.EMAIL_VERIFICATION,
            RefreshTokenSecret.hash(rawToken),
            Instant.now().plusSeconds(3600));
    when(tokens.findByTokenHash(RefreshTokenSecret.hash(rawToken))).thenReturn(Optional.of(token));
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));

    service.handle(new ConfirmEmailVerificationCommand(rawToken));

    assertThat(token.consumedAt()).isPresent();
    assertThat(account.emailVerifiedAt()).isPresent();
    verify(tokens).save(token);
    verify(accounts).save(account);
    verify(outbox).write(eq("account.email_verified"), eq(account.id()), any());
  }

  @Test
  void rejectsAnUnknownToken() {
    when(tokens.findByTokenHash(any())).thenReturn(Optional.empty());
    ConfirmEmailVerificationCommand command = new ConfirmEmailVerificationCommand("garbage");

    assertThatExceptionOfType(InvalidVerificationTokenException.class)
        .isThrownBy(() -> service.handle(command));

    verify(accounts, never()).save(any());
    verify(outbox, never()).write(any(), any(), any());
  }

  @Test
  void rejectsAnAlreadyConsumedToken() {
    String rawToken = "already-used-value";
    VerificationToken token =
        VerificationToken.issue(
            new AccountId(UUID.randomUUID()),
            VerificationTokenType.EMAIL_VERIFICATION,
            RefreshTokenSecret.hash(rawToken),
            Instant.now().plusSeconds(3600));
    token.consume();
    when(tokens.findByTokenHash(RefreshTokenSecret.hash(rawToken))).thenReturn(Optional.of(token));
    ConfirmEmailVerificationCommand command = new ConfirmEmailVerificationCommand(rawToken);

    assertThatExceptionOfType(InvalidVerificationTokenException.class)
        .isThrownBy(() -> service.handle(command));
  }

  @Test
  void rejectsAnExpiredToken() {
    String rawToken = "expired-value";
    VerificationToken token =
        VerificationToken.issue(
            new AccountId(UUID.randomUUID()),
            VerificationTokenType.EMAIL_VERIFICATION,
            RefreshTokenSecret.hash(rawToken),
            Instant.now().minusSeconds(1));
    when(tokens.findByTokenHash(RefreshTokenSecret.hash(rawToken))).thenReturn(Optional.of(token));
    ConfirmEmailVerificationCommand command = new ConfirmEmailVerificationCommand(rawToken);

    assertThatExceptionOfType(InvalidVerificationTokenException.class)
        .isThrownBy(() -> service.handle(command));
  }

  @Test
  void rejectsATokenOfTheWrongType() {
    String rawToken = "a-password-reset-token";
    VerificationToken token =
        VerificationToken.issue(
            new AccountId(UUID.randomUUID()),
            VerificationTokenType.PASSWORD_RESET,
            RefreshTokenSecret.hash(rawToken),
            Instant.now().plusSeconds(3600));
    when(tokens.findByTokenHash(RefreshTokenSecret.hash(rawToken))).thenReturn(Optional.of(token));
    ConfirmEmailVerificationCommand command = new ConfirmEmailVerificationCommand(rawToken);

    assertThatExceptionOfType(InvalidVerificationTokenException.class)
        .isThrownBy(() -> service.handle(command));

    verify(accounts, never()).save(any());
  }
}
