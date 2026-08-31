package com.clavaris.identity.application.usecase.authenticateplatformaccountwithsocialprovider;

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
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.PlatformMailSender;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.domain.model.PlatformSocialIdentity;
import com.clavaris.identity.domain.model.SocialProvider;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class AuthenticatePlatformAccountWithSocialProviderServiceTest {

  private static final Email EMAIL = new Email("founder@example.com");

  private PlatformAccountRepository accounts;
  private PlatformSocialIdentityRepository socialIdentities;
  private PendingPlatformSocialLinkRepository pendingLinks;
  private PlatformMailSender mailSender;
  private SecurityMetricsRecorder metrics;
  private AuthenticatePlatformAccountWithSocialProviderService service;

  @BeforeEach
  void setUp() {
    accounts = mock(PlatformAccountRepository.class);
    socialIdentities = mock(PlatformSocialIdentityRepository.class);
    pendingLinks = mock(PendingPlatformSocialLinkRepository.class);
    mailSender = mock(PlatformMailSender.class);
    metrics = mock(SecurityMetricsRecorder.class);

    // Same fake-immediate-execution TransactionTemplate as the tenant-tier sibling test's setup.
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
        new AuthenticatePlatformAccountWithSocialProviderService(
            accounts, socialIdentities, pendingLinks, mailSender, metrics, fakeTransactionTemplate);
  }

  private AuthenticatePlatformAccountWithSocialProviderCommand command() {
    return new AuthenticatePlatformAccountWithSocialProviderCommand(
        SocialProvider.GOOGLE, "google-sub-123", EMAIL, true);
  }

  @Test
  void logsInDirectlyWhenAnIdentityIsAlreadyLinked() {
    PlatformAccountId platformAccountId = PlatformAccountId.newId();
    when(socialIdentities.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "google-sub-123"))
        .thenReturn(
            Optional.of(
                PlatformSocialIdentity.link(
                    platformAccountId, SocialProvider.GOOGLE, "google-sub-123")));

    AuthenticatePlatformAccountWithSocialProviderResult result = service.handle(command());

    assertThat(result)
        .isInstanceOf(AuthenticatePlatformAccountWithSocialProviderResult.LoggedIn.class);
    assertThat(
            ((AuthenticatePlatformAccountWithSocialProviderResult.LoggedIn) result)
                .platformAccountId())
        .isEqualTo(platformAccountId);
    verify(accounts, never()).save(any());
  }

  @Test
  void createsABrandNewAccountAndLinksItImmediatelyWhenNoAccountExistsForTheEmail() {
    when(socialIdentities.findByProviderAndProviderUserId(any(), any()))
        .thenReturn(Optional.empty());
    when(accounts.findByEmail(EMAIL)).thenReturn(Optional.empty());

    AuthenticatePlatformAccountWithSocialProviderResult result = service.handle(command());

    assertThat(result)
        .isInstanceOf(AuthenticatePlatformAccountWithSocialProviderResult.LoggedIn.class);
    verify(accounts).save(any(PlatformAccount.class));
    verify(socialIdentities).save(any(PlatformSocialIdentity.class));
    verifyNoInteractions(mailSender);
  }

  @Test
  void fallsBackToPendingLinkWhenAConcurrentSignupWinsTheRaceForTheSameEmail() {
    // Code review finding: this tier was missing the same TOCTOU-race guard the tenant-tier
    // sibling already has — two concurrent first-time platform social logins for the same email
    // but different providers can both observe an empty existingAccount and both race into
    // linkBrandNewAccount. The loser must fall back to the same pending-link branch it would have
    // taken had it observed the winner first, not surface the unique-constraint violation as an
    // unhandled exception.
    when(socialIdentities.findByProviderAndProviderUserId(any(), any()))
        .thenReturn(Optional.empty());
    PlatformAccount winningAccount = PlatformAccount.register(EMAIL);
    when(accounts.findByEmail(EMAIL))
        .thenReturn(Optional.empty()) // handle()'s own initial check: race not yet visible
        .thenReturn(Optional.of(winningAccount)); // re-fetch after losing the race
    doThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"))
        .when(accounts)
        .save(any());

    AuthenticatePlatformAccountWithSocialProviderResult result = service.handle(command());

    assertThat(result)
        .isInstanceOf(
            AuthenticatePlatformAccountWithSocialProviderResult.ConfirmationRequired.class);
    verify(pendingLinks).save(any());
    verify(mailSender)
        .sendPlatformSocialLinkConfirmation(eq(EMAIL.value()), eq(SocialProvider.GOOGLE), any());
    verify(socialIdentities, never()).save(any());
  }

  @Test
  void raisesAPendingLinkAndEmailsTheExistingAddressWhenAnAccountAlreadyExists() {
    when(socialIdentities.findByProviderAndProviderUserId(any(), any()))
        .thenReturn(Optional.empty());
    PlatformAccount existing = PlatformAccount.register(EMAIL);
    when(accounts.findByEmail(EMAIL)).thenReturn(Optional.of(existing));

    AuthenticatePlatformAccountWithSocialProviderResult result = service.handle(command());

    assertThat(result)
        .isInstanceOf(
            AuthenticatePlatformAccountWithSocialProviderResult.ConfirmationRequired.class);
    verify(pendingLinks).save(any());
    verify(mailSender)
        .sendPlatformSocialLinkConfirmation(eq(EMAIL.value()), eq(SocialProvider.GOOGLE), any());
    verify(accounts, never()).save(any());
    verify(socialIdentities, never()).save(any());
  }

  @Test
  void rejectsAnUnverifiedProviderEmailBeforeTouchingAnyPort() {
    AuthenticatePlatformAccountWithSocialProviderCommand command =
        new AuthenticatePlatformAccountWithSocialProviderCommand(
            SocialProvider.GOOGLE, "google-sub-123", EMAIL, false);

    assertThatExceptionOfType(UnverifiedPlatformProviderEmailException.class)
        .isThrownBy(() -> service.handle(command));

    verifyNoInteractions(accounts, socialIdentities, pendingLinks, mailSender);
  }
}
