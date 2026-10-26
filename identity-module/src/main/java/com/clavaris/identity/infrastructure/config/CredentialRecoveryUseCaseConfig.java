package com.clavaris.identity.infrastructure.config;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.completeforcedpasswordreset.CompleteForcedPasswordResetService;
import com.clavaris.identity.application.usecase.completeforcedpasswordreset.CompleteForcedPasswordResetUseCase;
import com.clavaris.identity.application.usecase.confirmemailverification.ConfirmEmailVerificationService;
import com.clavaris.identity.application.usecase.confirmemailverification.ConfirmEmailVerificationUseCase;
import com.clavaris.identity.application.usecase.confirmpasswordreset.ConfirmPasswordResetService;
import com.clavaris.identity.application.usecase.confirmpasswordreset.ConfirmPasswordResetUseCase;
import com.clavaris.identity.application.usecase.forcepasswordresetforaccount.ForcePasswordResetForAccountService;
import com.clavaris.identity.application.usecase.forcepasswordresetforaccount.ForcePasswordResetForAccountUseCase;
import com.clavaris.identity.application.usecase.issuerefreshtoken.RefreshTokenRepository;
import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.BreachedPasswordChecker;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.registeraccount.PasswordHasher;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.application.usecase.requestemailverification.RequestEmailVerificationService;
import com.clavaris.identity.application.usecase.requestemailverification.RequestEmailVerificationUseCase;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.application.usecase.requestpasswordreset.RequestPasswordResetService;
import com.clavaris.identity.application.usecase.requestpasswordreset.RequestPasswordResetUseCase;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountSessionRevoker;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountTokenRevoker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the identity use cases for: email verification and password recovery, including
 * operator-forced resets.
 *
 * <p>Deliberately kept out of {@code application/usecase} itself: a service's only Spring
 * dependency is {@code @Transactional} on its {@code handle} method
 * (first-vertical-slice-blueprint.md §2.3), so bean registration lives here, in infrastructure.
 * Split from one 868-line {@code IdentityUseCaseConfig} by area; beans reach each other through
 * ordinary constructor injection, so the split changes no wiring.
 */
@Configuration
class CredentialRecoveryUseCaseConfig {

  // Written out explicitly for the same reason as Argon2PasswordHasher's own constructor
  // (see its comment) — only Spring's own component scan ever needs to instantiate this class.
  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ CredentialRecoveryUseCaseConfig() {
    // Intentionally empty — this class holds no state, only @Bean methods.
  }

  @Bean
  /* package */ RequestEmailVerificationUseCase requestEmailVerificationUseCase(
      final AccountRepository accounts,
      final VerificationTokenRepository verificationTokens,
      final MailSender mailSender,
      final AccountAuthenticationPolicyProvider policyProvider) {
    return new RequestEmailVerificationService(
        accounts, verificationTokens, mailSender, policyProvider);
  }

  @Bean
  /* package */ ConfirmEmailVerificationUseCase confirmEmailVerificationUseCase(
      final VerificationTokenRepository verificationTokens,
      final AccountRepository accounts,
      final EventOutboxWriter eventOutboxWriter) {
    return new ConfirmEmailVerificationService(verificationTokens, accounts, eventOutboxWriter);
  }

  @Bean
  /* package */ RequestPasswordResetUseCase requestPasswordResetUseCase(
      final AccountRepository accounts,
      final VerificationTokenRepository verificationTokens,
      final MailSender mailSender,
      final EventOutboxWriter eventOutboxWriter) {
    return new RequestPasswordResetService(
        accounts, verificationTokens, mailSender, eventOutboxWriter);
  }

  @Bean
  /* package */ ConfirmPasswordResetUseCase confirmPasswordResetUseCase(
      final VerificationTokenRepository verificationTokens,
      final AccountRepository accounts,
      final SessionRepository sessions,
      final RefreshTokenRepository refreshTokens,
      final AccountTokenRevoker accountTokenRevoker,
      final AccountSessionRevoker accountSessionRevoker,
      final PasswordHasher passwordHasher,
      final EventOutboxWriter eventOutboxWriter,
      final BreachedPasswordChecker breachedPasswordChecker) {
    return new ConfirmPasswordResetService(
        verificationTokens,
        accounts,
        sessions,
        refreshTokens,
        accountTokenRevoker,
        accountSessionRevoker,
        passwordHasher,
        eventOutboxWriter,
        breachedPasswordChecker);
  }

  // Clerk "session tasks" parity
  @Bean
  /* package */ ForcePasswordResetForAccountUseCase forcePasswordResetForAccountUseCase(
      final AccountRepository accounts, final AuditEventRecorder auditEvents) {
    return new ForcePasswordResetForAccountService(accounts, auditEvents);
  }

  // Clerk "session tasks" parity
  @Bean
  /* package */ CompleteForcedPasswordResetUseCase completeForcedPasswordResetUseCase(
      final AccountRepository accounts,
      final SessionRepository sessions,
      final RefreshTokenRepository refreshTokens,
      final AccountTokenRevoker accountTokenRevoker,
      final AccountSessionRevoker accountSessionRevoker,
      final PasswordHasher hasher,
      final BreachedPasswordChecker breachedPasswordChecker) {
    return new CompleteForcedPasswordResetService(
        accounts,
        sessions,
        refreshTokens,
        accountTokenRevoker,
        accountSessionRevoker,
        hasher,
        breachedPasswordChecker);
  }
}
