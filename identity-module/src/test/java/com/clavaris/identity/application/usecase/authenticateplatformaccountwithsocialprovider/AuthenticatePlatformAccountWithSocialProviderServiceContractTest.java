package com.clavaris.identity.application.usecase.authenticateplatformaccountwithsocialprovider;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.identity.application.usecase.SocialLoginLinkingContractTest;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.PlatformMailSender;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.domain.model.PlatformSocialIdentity;
import com.clavaris.identity.domain.model.SocialProvider;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** Platform-tier fixture for {@link SocialLoginLinkingContractTest} — see its own Javadoc. */
class AuthenticatePlatformAccountWithSocialProviderServiceContractTest
    extends SocialLoginLinkingContractTest<AuthenticatePlatformAccountWithSocialProviderResult> {

  private static final Email EMAIL = new Email("contract-test-founder@example.com");
  private static final String PROVIDER_USER_ID = "contract-test-google-sub";

  private PlatformAccountRepository accounts;
  private PlatformSocialIdentityRepository socialIdentities;
  private PendingPlatformSocialLinkRepository pendingLinks;
  private AuthenticatePlatformAccountWithSocialProviderService service;

  @BeforeEach
  void setUp() {
    accounts = mock(PlatformAccountRepository.class);
    socialIdentities = mock(PlatformSocialIdentityRepository.class);
    pendingLinks = mock(PendingPlatformSocialLinkRepository.class);
    PlatformMailSender mailSender = mock(PlatformMailSender.class);
    SecurityMetricsRecorder metrics = mock(SecurityMetricsRecorder.class);

    // Same fake-immediate-execution TransactionTemplate as this service's own dedicated unit
    // test — this contract test never touches a real database either.
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

  private AuthenticatePlatformAccountWithSocialProviderCommand command(
      final boolean emailVerified) {
    return new AuthenticatePlatformAccountWithSocialProviderCommand(
        SocialProvider.GOOGLE, PROVIDER_USER_ID, EMAIL, emailVerified);
  }

  @Override
  protected void givenNoExistingIdentity() {
    when(socialIdentities.findByProviderAndProviderUserId(any(), any()))
        .thenReturn(Optional.empty());
  }

  @Override
  protected void givenAnExistingIdentityIsFound() {
    PlatformAccountId platformAccountId = PlatformAccountId.newId();
    when(socialIdentities.findByProviderAndProviderUserId(SocialProvider.GOOGLE, PROVIDER_USER_ID))
        .thenReturn(
            Optional.of(
                PlatformSocialIdentity.link(
                    platformAccountId, SocialProvider.GOOGLE, PROVIDER_USER_ID)));
  }

  @Override
  protected void givenNoExistingAccountForTheEmail() {
    when(accounts.findByEmail(EMAIL)).thenReturn(Optional.empty());
  }

  @Override
  protected void givenAnExistingAccountForTheEmail() {
    PlatformAccount existing = PlatformAccount.register(EMAIL);
    when(accounts.findByEmail(EMAIL)).thenReturn(Optional.of(existing));
  }

  @Override
  protected void givenTheAccountSaveRacesAndLoses() {
    PlatformAccount winningAccount = PlatformAccount.register(EMAIL);
    when(accounts.findByEmail(EMAIL))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(winningAccount));
    doThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"))
        .when(accounts)
        .save(any());
  }

  @Override
  protected AuthenticatePlatformAccountWithSocialProviderResult invokeWithVerifiedEmail(
      final boolean verified) {
    return service.handle(command(verified));
  }

  @Override
  protected boolean isLoggedIn(final AuthenticatePlatformAccountWithSocialProviderResult result) {
    return result instanceof AuthenticatePlatformAccountWithSocialProviderResult.LoggedIn;
  }

  @Override
  protected boolean isConfirmationRequired(
      final AuthenticatePlatformAccountWithSocialProviderResult result) {
    return result
        instanceof AuthenticatePlatformAccountWithSocialProviderResult.ConfirmationRequired;
  }

  @Override
  protected void verifyNoIdentityWasEverSaved() {
    verify(socialIdentities, never()).save(any());
  }

  @Override
  protected void verifyAPendingLinkWasSaved() {
    verify(pendingLinks).save(any());
  }
}
