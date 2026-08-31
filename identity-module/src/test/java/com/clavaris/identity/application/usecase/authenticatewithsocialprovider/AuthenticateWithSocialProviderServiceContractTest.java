package com.clavaris.identity.application.usecase.authenticatewithsocialprovider;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.identity.application.usecase.SocialLoginLinkingContractTest;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** Tenant-tier fixture for {@link SocialLoginLinkingContractTest} — see its own Javadoc. */
class AuthenticateWithSocialProviderServiceContractTest
    extends SocialLoginLinkingContractTest<AuthenticateWithSocialProviderResult> {

  private static final OrganizationId ORGANIZATION_ID = new OrganizationId(UUID.randomUUID());
  private static final Email EMAIL = new Email("contract-test@example.com");
  private static final String PROVIDER_USER_ID = "contract-test-google-sub";

  private AccountRepository accounts;
  private SocialIdentityRepository socialIdentities;
  private PendingSocialLinkRepository pendingLinks;
  private AuthenticateWithSocialProviderService service;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    socialIdentities = mock(SocialIdentityRepository.class);
    pendingLinks = mock(PendingSocialLinkRepository.class);
    OrganizationSocialLoginPolicyProvider policyProvider =
        mock(OrganizationSocialLoginPolicyProvider.class);
    MailSender mailSender = mock(MailSender.class);
    EventOutboxWriter outbox = mock(EventOutboxWriter.class);
    SecurityMetricsRecorder metrics = mock(SecurityMetricsRecorder.class);

    when(policyProvider.isProviderAllowed(ORGANIZATION_ID, SocialProvider.GOOGLE)).thenReturn(true);

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

  private AuthenticateWithSocialProviderCommand command(final boolean emailVerified) {
    return new AuthenticateWithSocialProviderCommand(
        ORGANIZATION_ID, SocialProvider.GOOGLE, PROVIDER_USER_ID, EMAIL, emailVerified);
  }

  @Override
  protected void givenNoExistingIdentity() {
    when(socialIdentities.findByOrganizationIdAndProviderAndProviderUserId(any(), any(), any()))
        .thenReturn(Optional.empty());
  }

  @Override
  protected void givenAnExistingIdentityIsFound() {
    AccountId accountId = AccountId.newId();
    when(socialIdentities.findByOrganizationIdAndProviderAndProviderUserId(
            ORGANIZATION_ID, SocialProvider.GOOGLE, PROVIDER_USER_ID))
        .thenReturn(
            Optional.of(
                SocialIdentity.link(
                    accountId, ORGANIZATION_ID, SocialProvider.GOOGLE, PROVIDER_USER_ID)));
  }

  @Override
  protected void givenNoExistingAccountForTheEmail() {
    when(accounts.findByOrganizationIdAndEmail(ORGANIZATION_ID, EMAIL))
        .thenReturn(Optional.empty());
  }

  @Override
  protected void givenAnExistingAccountForTheEmail() {
    Account existing = Account.register(ORGANIZATION_ID, EMAIL);
    when(accounts.findByOrganizationIdAndEmail(ORGANIZATION_ID, EMAIL))
        .thenReturn(Optional.of(existing));
  }

  @Override
  protected void givenTheAccountSaveRacesAndLoses() {
    Account winningAccount = Account.register(ORGANIZATION_ID, EMAIL);
    when(accounts.findByOrganizationIdAndEmail(ORGANIZATION_ID, EMAIL))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(winningAccount));
    doThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"))
        .when(accounts)
        .save(any());
  }

  @Override
  protected AuthenticateWithSocialProviderResult invokeWithVerifiedEmail(final boolean verified) {
    return service.handle(command(verified));
  }

  @Override
  protected boolean isLoggedIn(final AuthenticateWithSocialProviderResult result) {
    return result instanceof AuthenticateWithSocialProviderResult.LoggedIn;
  }

  @Override
  protected boolean isConfirmationRequired(final AuthenticateWithSocialProviderResult result) {
    return result instanceof AuthenticateWithSocialProviderResult.ConfirmationRequired;
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
