package com.clavaris.identity.application.usecase.authenticatewithsocialprovider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SocialIdentity;
import com.clavaris.identity.domain.model.SocialProvider;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class AuthenticateWithSocialProviderServiceTest {

  private static final OrganizationId ORGANIZATION_ID = new OrganizationId(UUID.randomUUID());
  private static final Email EMAIL = new Email("user@example.com");

  private AccountRepository accounts;
  private SocialIdentityRepository socialIdentities;
  private PendingSocialLinkRepository pendingLinks;
  private OrganizationSocialLoginPolicyProvider policyProvider;
  private MailSender mailSender;
  private EventOutboxWriter outbox;
  private SecurityMetricsRecorder metrics;
  private AuthenticateWithSocialProviderService service;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    socialIdentities = mock(SocialIdentityRepository.class);
    pendingLinks = mock(PendingSocialLinkRepository.class);
    policyProvider = mock(OrganizationSocialLoginPolicyProvider.class);
    mailSender = mock(MailSender.class);
    outbox = mock(EventOutboxWriter.class);
    metrics = mock(SecurityMetricsRecorder.class);

    when(policyProvider.isProviderAllowed(ORGANIZATION_ID, SocialProvider.GOOGLE)).thenReturn(true);

    // Same fake-immediate-execution TransactionTemplate as AddWorkspaceMemberServiceTest's own
    // setup — this unit test never touches a real database.
    PlatformTransactionManager fakeTransactionManager = mock(PlatformTransactionManager.class);
    TransactionTemplate fakeTransactionTemplate =
        new TransactionTemplate(fakeTransactionManager) {
          @Override
          public <T> T execute(final TransactionCallback<T> action) {
            TransactionStatus status = new SimpleTransactionStatus();
            return action.doInTransaction(status);
          }
        };

    service =
        new AuthenticateWithSocialProviderService(
            accounts,
            socialIdentities,
            pendingLinks,
            policyProvider,
            mailSender,
            outbox,
            metrics,
            fakeTransactionTemplate);
  }

  private AuthenticateWithSocialProviderCommand command() {
    return new AuthenticateWithSocialProviderCommand(
        ORGANIZATION_ID, SocialProvider.GOOGLE, "google-sub-123", EMAIL, true);
  }

  @Test
  void logsInDirectlyWhenAnIdentityIsAlreadyLinked() {
    AccountId accountId = AccountId.newId();
    when(socialIdentities.findByOrganizationIdAndProviderAndProviderUserId(
            ORGANIZATION_ID, SocialProvider.GOOGLE, "google-sub-123"))
        .thenReturn(
            Optional.of(
                SocialIdentity.link(
                    accountId, ORGANIZATION_ID, SocialProvider.GOOGLE, "google-sub-123")));

    AuthenticateWithSocialProviderResult result = service.handle(command());

    assertThat(result).isInstanceOf(AuthenticateWithSocialProviderResult.LoggedIn.class);
    assertThat(((AuthenticateWithSocialProviderResult.LoggedIn) result).accountId())
        .isEqualTo(accountId);
    verify(accounts, never()).save(any());
  }

  @Test
  void createsABrandNewAccountAndLinksItImmediatelyWhenNoAccountExistsForTheEmail() {
    when(socialIdentities.findByOrganizationIdAndProviderAndProviderUserId(any(), any(), any()))
        .thenReturn(Optional.empty());
    when(accounts.findByOrganizationIdAndEmail(ORGANIZATION_ID, EMAIL))
        .thenReturn(Optional.empty());

    AuthenticateWithSocialProviderResult result = service.handle(command());

    assertThat(result).isInstanceOf(AuthenticateWithSocialProviderResult.LoggedIn.class);
    verify(accounts).save(any(Account.class));
    verify(socialIdentities).save(any(SocialIdentity.class));
    verify(outbox).write(eq("account.created"), any(), any());
    verify(outbox).write(eq("social_identity.linked"), any(), any());
    verifyNoInteractions(mailSender);
  }

  @Test
  void fallsBackToPendingLinkWhenAConcurrentSignupWinsTheRaceForTheSameEmail() {
    // Code review finding, TOCTOU: handle()'s own existingAccount check and linkBrandNewAccount's
    // own save() are not atomic with each other — two concurrent first-time social logins for the
    // same (organizationId, email) but different providers can both observe an empty
    // existingAccount and both race into linkBrandNewAccount. The loser must fall back to the
    // same pending-link branch it would have taken had it observed the winner first, not surface
    // the unique-constraint violation as an unhandled exception.
    when(socialIdentities.findByOrganizationIdAndProviderAndProviderUserId(any(), any(), any()))
        .thenReturn(Optional.empty());
    Account winningAccount = Account.register(ORGANIZATION_ID, EMAIL);
    when(accounts.findByOrganizationIdAndEmail(ORGANIZATION_ID, EMAIL))
        .thenReturn(Optional.empty()) // handle()'s own initial check: race not yet visible
        .thenReturn(Optional.of(winningAccount)); // re-fetch after losing the race
    doThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"))
        .when(accounts)
        .save(any());

    AuthenticateWithSocialProviderResult result = service.handle(command());

    assertThat(result)
        .isInstanceOf(AuthenticateWithSocialProviderResult.ConfirmationRequired.class);
    verify(pendingLinks).save(any());
    verify(mailSender)
        .sendSocialLinkConfirmation(
            eq(EMAIL.value()), eq(ORGANIZATION_ID), eq(SocialProvider.GOOGLE), any());
    verify(socialIdentities, never()).save(any());
  }

  @Test
  void raisesAPendingLinkAndEmailsTheExistingAddressWhenAnAccountAlreadyExists() {
    when(socialIdentities.findByOrganizationIdAndProviderAndProviderUserId(any(), any(), any()))
        .thenReturn(Optional.empty());
    Account existing = Account.register(ORGANIZATION_ID, EMAIL);
    when(accounts.findByOrganizationIdAndEmail(ORGANIZATION_ID, EMAIL))
        .thenReturn(Optional.of(existing));

    AuthenticateWithSocialProviderResult result = service.handle(command());

    assertThat(result)
        .isInstanceOf(AuthenticateWithSocialProviderResult.ConfirmationRequired.class);
    verify(pendingLinks).save(any());
    verify(mailSender)
        .sendSocialLinkConfirmation(
            eq(EMAIL.value()), eq(ORGANIZATION_ID), eq(SocialProvider.GOOGLE), any());
    verify(accounts, never()).save(any());
    verify(socialIdentities, never()).save(any());
  }

  @Test
  void rejectsAnUnverifiedProviderEmailBeforeTouchingAnyPort() {
    AuthenticateWithSocialProviderCommand command =
        new AuthenticateWithSocialProviderCommand(
            ORGANIZATION_ID, SocialProvider.GOOGLE, "google-sub-123", EMAIL, false);

    assertThatExceptionOfType(UnverifiedProviderEmailException.class)
        .isThrownBy(() -> service.handle(command));

    verifyNoInteractions(accounts, socialIdentities, pendingLinks, mailSender, outbox);
  }

  @Test
  void rejectsAProviderTheOrganizationHasNotEnabled() {
    when(policyProvider.isProviderAllowed(ORGANIZATION_ID, SocialProvider.GITHUB))
        .thenReturn(false);
    AuthenticateWithSocialProviderCommand command =
        new AuthenticateWithSocialProviderCommand(
            ORGANIZATION_ID, SocialProvider.GITHUB, "gh-456", EMAIL, true);

    assertThatExceptionOfType(SocialLoginNotAllowedException.class)
        .isThrownBy(() -> service.handle(command));

    verifyNoInteractions(accounts, socialIdentities, pendingLinks, mailSender, outbox);
  }
}
