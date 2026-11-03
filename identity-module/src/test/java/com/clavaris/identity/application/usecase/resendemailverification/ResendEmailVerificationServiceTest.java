package com.clavaris.identity.application.usecase.resendemailverification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicySnapshot;
import com.clavaris.identity.application.usecase.requestemailverification.EmailVerificationMethod;
import com.clavaris.identity.application.usecase.requestemailverification.RequestEmailVerificationCommand;
import com.clavaris.identity.application.usecase.requestemailverification.RequestEmailVerificationService;
import com.clavaris.identity.application.usecase.requestemailverification.RequestEmailVerificationUseCase;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.Username;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionOperations;

/**
 * TD-FUT-055: the resend sends only to an active, unverified Account, ends the earlier message
 * first, keeps the 30-second wait, and does the same thing, silently, for everything else.
 */
class ResendEmailVerificationServiceTest {

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
  private final Email email = new Email("lost-mail@example.com");

  private AccountRepository accounts;
  private VerificationTokenRepository tokens;
  private RequestEmailVerificationUseCase requestEmailVerification;
  private AccountAuthenticationPolicyProvider policyProvider;
  private ResendEmailVerificationService service;
  private final List<Runnable> deferred = new ArrayList<>();

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    tokens = mock(VerificationTokenRepository.class);
    requestEmailVerification = mock(RequestEmailVerificationUseCase.class);
    policyProvider = mock(AccountAuthenticationPolicyProvider.class);
    when(policyProvider.policyFor(organizationId)).thenReturn(policy(true));
    service =
        new ResendEmailVerificationService(
            accounts,
            tokens,
            requestEmailVerification,
            policyProvider,
            Runnable::run,
            TransactionOperations.withoutTransaction());
  }

  private static AccountAuthenticationPolicySnapshot policy(final boolean usernameSignIn) {
    return new AccountAuthenticationPolicySnapshot(
        true,
        EmailVerificationMethod.LINK,
        false,
        false,
        false,
        usernameSignIn,
        true,
        false,
        false);
  }

  private Account unverifiedAccount() {
    Account account = Account.register(organizationId, email);
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    return account;
  }

  @Test
  void endsTheEarlierMessageThenSendsANewOneToAnUnverifiedAccount() {
    Account account = unverifiedAccount();

    service.handle(new ResendEmailVerificationCommand(organizationId, email.value()));

    InOrder order = inOrder(tokens, requestEmailVerification);
    order
        .verify(tokens)
        .invalidateActiveOfTypeForAccount(
            eq(account.id()), eq(VerificationTokenType.EMAIL_VERIFICATION), any(Instant.class));
    order
        .verify(requestEmailVerification)
        .handle(new RequestEmailVerificationCommand(account.id()));
  }

  @Test
  void anAccountThatIsAlreadyVerifiedGetsNothing() {
    Account account = unverifiedAccount();
    account.verifyEmail();

    service.handle(new ResendEmailVerificationCommand(organizationId, email.value()));

    verify(requestEmailVerification, never()).handle(any());
    verify(tokens, never()).invalidateActiveOfTypeForAccount(any(), any(), any());
  }

  @Test
  void anAddressWithNoAccountIsAnsweredLikeAnyOtherAndSendsNothing() {
    when(accounts.findByOrganizationIdAndEmail(organizationId, email)).thenReturn(Optional.empty());

    service.handle(new ResendEmailVerificationCommand(organizationId, email.value()));

    verify(requestEmailVerification, never()).handle(any());
  }

  // A suspended, banned or still-pending Account must not be handed a way back in by mail.
  @Test
  void anAccountThatIsNotActiveGetsNothing() {
    Account account = unverifiedAccount();
    account.suspend();

    service.handle(new ResendEmailVerificationCommand(organizationId, email.value()));

    verify(requestEmailVerification, never()).handle(any());
  }

  // Asked again inside the wait: nothing new is sent and the earlier message stays valid.
  @Test
  void sendsNothingNewWhenAskedAgainWithinTheWait() {
    Account account = unverifiedAccount();
    when(tokens.findLatestActiveOfTypeForAccount(
            eq(account.id()), eq(VerificationTokenType.EMAIL_VERIFICATION), any(Instant.class)))
        .thenReturn(
            Optional.of(
                VerificationToken.issue(
                    account.id(),
                    VerificationTokenType.EMAIL_VERIFICATION,
                    "earlier-hash",
                    Instant.now()
                        .plus(RequestEmailVerificationService.TOKEN_TTL)
                        .minusSeconds(10))));

    service.handle(new ResendEmailVerificationCommand(organizationId, email.value()));

    verify(tokens, never()).invalidateActiveOfTypeForAccount(any(), any(), any());
    verify(requestEmailVerification, never()).handle(any());
  }

  @Test
  void sendsANewOneOnceTheWaitHasPassed() {
    Account account = unverifiedAccount();
    when(tokens.findLatestActiveOfTypeForAccount(
            eq(account.id()), eq(VerificationTokenType.EMAIL_VERIFICATION), any(Instant.class)))
        .thenReturn(
            Optional.of(
                VerificationToken.issue(
                    account.id(),
                    VerificationTokenType.EMAIL_VERIFICATION,
                    "earlier-hash",
                    Instant.now()
                        .plus(RequestEmailVerificationService.TOKEN_TTL)
                        .minusSeconds(45))));

    service.handle(new ResendEmailVerificationCommand(organizationId, email.value()));

    verify(requestEmailVerification).handle(new RequestEmailVerificationCommand(account.id()));
  }

  // ADR-0037's rule: a username names the Account where username sign-in is on.
  @Test
  void aUsernameNamesTheAccountWhereUsernameSignInIsOn() {
    Account account = Account.register(organizationId, email);
    when(accounts.findByOrganizationIdAndUsername(organizationId, new Username("ana_perez")))
        .thenReturn(Optional.of(account));

    service.handle(new ResendEmailVerificationCommand(organizationId, "Ana_Perez"));

    verify(requestEmailVerification).handle(new RequestEmailVerificationCommand(account.id()));
  }

  @Test
  void aUsernameIsNoAccountWhereUsernameSignInIsOff() {
    when(policyProvider.policyFor(organizationId)).thenReturn(policy(false));

    service.handle(new ResendEmailVerificationCommand(organizationId, "ana_perez"));

    verify(accounts, never()).findByOrganizationIdAndUsername(any(), any());
    verify(requestEmailVerification, never()).handle(any());
  }

  // The account-dependent work runs after the caller has been answered, on the bounded pool.
  @Test
  void theAccountDependentWorkRunsOnThePoolNotOnTheCallersThread() {
    service =
        new ResendEmailVerificationService(
            accounts,
            tokens,
            requestEmailVerification,
            policyProvider,
            deferred::add,
            TransactionOperations.withoutTransaction());
    unverifiedAccount();

    service.handle(new ResendEmailVerificationCommand(organizationId, email.value()));

    org.mockito.Mockito.verifyNoInteractions(requestEmailVerification, tokens);
    assertThat(deferred).hasSize(1);
  }

  @Test
  void aSaturatedPoolIsLoggedAndTheCallerStillGetsTheSameAnswer() {
    service =
        new ResendEmailVerificationService(
            accounts,
            tokens,
            requestEmailVerification,
            policyProvider,
            task -> {
              throw new RejectedExecutionException("full");
            },
            TransactionOperations.withoutTransaction());

    service.handle(new ResendEmailVerificationCommand(organizationId, email.value()));

    verify(requestEmailVerification, never()).handle(any());
  }

  // Nothing thrown on the pool's thread can reach the caller.
  @Test
  void aFailureWhileSendingNeverReachesTheCaller() {
    Account account = unverifiedAccount();
    doThrow(new IllegalStateException("provider down"))
        .when(requestEmailVerification)
        .handle(new RequestEmailVerificationCommand(account.id()));
    final ResendEmailVerificationCommand command =
        new ResendEmailVerificationCommand(organizationId, email.value());

    assertThatNoException().isThrownBy(() -> service.handle(command));

    verify(requestEmailVerification).handle(new RequestEmailVerificationCommand(account.id()));
  }
}
