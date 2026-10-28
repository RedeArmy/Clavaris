package com.clavaris.identity.infrastructure.config;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.authenticatewithpassword.PasswordVerifier;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.PendingSocialLinkRepository;
import com.clavaris.identity.application.usecase.changepassword.ChangePasswordService;
import com.clavaris.identity.application.usecase.changepassword.ChangePasswordUseCase;
import com.clavaris.identity.application.usecase.confirmemailchange.ConfirmEmailChangeService;
import com.clavaris.identity.application.usecase.confirmemailchange.ConfirmEmailChangeUseCase;
import com.clavaris.identity.application.usecase.issuerefreshtoken.RefreshTokenRepository;
import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionRepository;
import com.clavaris.identity.application.usecase.listactivesessionsforaccount.AccountActiveSessionsRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.BreachedPasswordChecker;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.registeraccount.PasswordHasher;
import com.clavaris.identity.application.usecase.requestemailchange.EmailChangeRequestRepository;
import com.clavaris.identity.application.usecase.requestemailchange.RequestEmailChangeService;
import com.clavaris.identity.application.usecase.requestemailchange.RequestEmailChangeUseCase;
import com.clavaris.identity.application.usecase.requestemailchange.SelfServiceEmailChangePolicy;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountTokenRevoker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * TD-FUT-043: the signed-in End-User's own credential changes (password, email), kept apart from
 * {@link CredentialRecoveryUseCaseConfig}, which wires the signed-out recovery flows.
 */
@Configuration
class SelfServiceCredentialUseCaseConfig {

  // Same explicit constructor as CredentialRecoveryUseCaseConfig, same reason.
  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ SelfServiceCredentialUseCaseConfig() {
    // Intentionally empty — this class holds no state, only @Bean methods.
  }

  // Programmatic, not @Transactional on the services: these use cases open a transaction only
  // around their writes, keeping Argon2 waits, the breach lookup and mail sending outside it.
  @Bean
  /* package */ TransactionOperations selfServiceTransaction(
      final PlatformTransactionManager transactionManager) {
    return new TransactionTemplate(transactionManager);
  }

  @Bean
  @SuppressWarnings({
    "java:S107",
    "PMD.ExcessiveParameterList"
  }) // one parameter per collaborating port.
  /* package */ ChangePasswordUseCase changePasswordUseCase(
      final AccountRepository accounts,
      final PasswordVerifier verifier,
      final PasswordHasher hasher,
      final BreachedPasswordChecker breachedPasswordChecker,
      final SessionRepository sessions,
      final RefreshTokenRepository refreshTokens,
      final AccountTokenRevoker accountTokenRevoker,
      final AccountActiveSessionsRepository activeSessions,
      final EventOutboxWriter outbox,
      final AuditEventRecorder auditEvents,
      final MailSender mailSender,
      final TransactionOperations transaction) {
    return new ChangePasswordService(
        accounts,
        verifier,
        hasher,
        breachedPasswordChecker,
        sessions,
        refreshTokens,
        accountTokenRevoker,
        activeSessions,
        outbox,
        auditEvents,
        mailSender,
        transaction);
  }

  @Bean
  /* package */ RequestEmailChangeUseCase requestEmailChangeUseCase(
      final AccountRepository accounts,
      final PasswordVerifier verifier,
      final EmailChangeRequestRepository requests,
      final MailSender mailSender,
      final AuditEventRecorder auditEvents,
      final SelfServiceEmailChangePolicy policy,
      final TransactionOperations transaction) {
    return new RequestEmailChangeService(
        accounts, verifier, requests, mailSender, auditEvents, policy, transaction);
  }

  @Bean
  /* package */ ConfirmEmailChangeUseCase confirmEmailChangeUseCase(
      final EmailChangeRequestRepository requests,
      final AccountRepository accounts,
      final VerificationTokenRepository verificationTokens,
      final PendingSocialLinkRepository pendingLinks,
      final EventOutboxWriter outbox,
      final AuditEventRecorder auditEvents,
      final MailSender mailSender,
      final SelfServiceEmailChangePolicy policy,
      final TransactionOperations transaction) {
    return new ConfirmEmailChangeService(
        requests,
        accounts,
        verificationTokens,
        pendingLinks,
        outbox,
        auditEvents,
        mailSender,
        policy,
        transaction);
  }
}
