package com.clavaris.identity.infrastructure.config;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.codelockout.CodeLockout;
import com.clavaris.identity.application.usecase.confirmtotpenrollment.ConfirmTotpEnrollmentService;
import com.clavaris.identity.application.usecase.confirmtotpenrollment.ConfirmTotpEnrollmentUseCase;
import com.clavaris.identity.application.usecase.disabletotp.DisableTotpService;
import com.clavaris.identity.application.usecase.disabletotp.DisableTotpUseCase;
import com.clavaris.identity.application.usecase.gettotpstatus.GetTotpStatusService;
import com.clavaris.identity.application.usecase.gettotpstatus.GetTotpStatusUseCase;
import com.clavaris.identity.application.usecase.regeneraterecoverycodes.RegenerateRecoveryCodesService;
import com.clavaris.identity.application.usecase.regeneraterecoverycodes.RegenerateRecoveryCodesUseCase;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.requiresecondfactor.RequiresSecondFactorService;
import com.clavaris.identity.application.usecase.requiresecondfactor.RequiresSecondFactorUseCase;
import com.clavaris.identity.application.usecase.resettotpforaccount.ResetTotpForAccountService;
import com.clavaris.identity.application.usecase.resettotpforaccount.ResetTotpForAccountUseCase;
import com.clavaris.identity.application.usecase.starttotpenrollment.StartTotpEnrollmentService;
import com.clavaris.identity.application.usecase.starttotpenrollment.StartTotpEnrollmentUseCase;
import com.clavaris.identity.application.usecase.starttotpenrollment.TotpCredentialRepository;
import com.clavaris.identity.application.usecase.starttotpenrollment.TotpRecoveryCodeRepository;
import com.clavaris.identity.application.usecase.starttotpenrollment.TotpSecretCipher;
import com.clavaris.identity.application.usecase.verifytotpchallenge.TotpCodeVerifier;
import com.clavaris.identity.application.usecase.verifytotpchallenge.VerifyTotpChallengeService;
import com.clavaris.identity.application.usecase.verifytotpchallenge.VerifyTotpChallengeUseCase;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the authenticator-app second factor (ADR-0040, TD-FUT-056). Each use case writes in a
 * {@code TransactionOperations} of its own around the write only, never across the hash or a
 * network call, as the other services do.
 */
@Configuration
class TotpUseCaseConfig {

  /* package */ TotpUseCaseConfig() {
    // Intentionally empty — this class holds no state, only @Bean methods.
  }

  @Bean
  /* package */ TotpCodeVerifier totpCodeVerifier(
      final TotpCredentialRepository credentials,
      final TotpRecoveryCodeRepository recoveryCodes,
      final TotpSecretCipher cipher,
      final CodeLockout lockout,
      final AuditEventRecorder auditEvents) {
    return new TotpCodeVerifier(
        credentials, recoveryCodes, cipher, lockout, auditEvents, Clock.systemUTC());
  }

  @Bean
  /* package */ StartTotpEnrollmentUseCase startTotpEnrollmentUseCase(
      final AccountRepository accounts,
      final AccountAuthenticationPolicyProvider policyProvider,
      final TotpCredentialRepository credentials,
      final TotpSecretCipher cipher,
      final PlatformTransactionManager transactionManager) {
    return new StartTotpEnrollmentService(
        accounts, policyProvider, credentials, cipher, new TransactionTemplate(transactionManager));
  }

  @Bean
  /* package */ ConfirmTotpEnrollmentUseCase confirmTotpEnrollmentUseCase(
      final TotpCredentialRepository credentials,
      final TotpRecoveryCodeRepository recoveryCodes,
      final TotpSecretCipher cipher,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox,
      final PlatformTransactionManager transactionManager) {
    return new ConfirmTotpEnrollmentService(
        credentials,
        recoveryCodes,
        cipher,
        auditEvents,
        outbox,
        Clock.systemUTC(),
        new TransactionTemplate(transactionManager));
  }

  @Bean
  /* package */ VerifyTotpChallengeUseCase verifyTotpChallengeUseCase(
      final TotpCodeVerifier verifier, final PlatformTransactionManager transactionManager) {
    return new VerifyTotpChallengeService(verifier, new TransactionTemplate(transactionManager));
  }

  @Bean
  /* package */ DisableTotpUseCase disableTotpUseCase(
      final TotpCodeVerifier verifier,
      final TotpCredentialRepository credentials,
      final TotpRecoveryCodeRepository recoveryCodes,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox,
      final PlatformTransactionManager transactionManager) {
    return new DisableTotpService(
        verifier,
        credentials,
        recoveryCodes,
        auditEvents,
        outbox,
        new TransactionTemplate(transactionManager));
  }

  @Bean
  /* package */ RegenerateRecoveryCodesUseCase regenerateRecoveryCodesUseCase(
      final TotpCodeVerifier verifier,
      final TotpCredentialRepository credentials,
      final TotpRecoveryCodeRepository recoveryCodes,
      final AuditEventRecorder auditEvents,
      final PlatformTransactionManager transactionManager) {
    return new RegenerateRecoveryCodesService(
        verifier,
        credentials,
        recoveryCodes,
        auditEvents,
        new TransactionTemplate(transactionManager));
  }

  @Bean
  /* package */ ResetTotpForAccountUseCase resetTotpForAccountUseCase(
      final AccountRepository accounts,
      final TotpCredentialRepository credentials,
      final TotpRecoveryCodeRepository recoveryCodes,
      final CodeLockout lockout,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox,
      final PlatformTransactionManager transactionManager) {
    return new ResetTotpForAccountService(
        accounts,
        credentials,
        recoveryCodes,
        lockout,
        auditEvents,
        outbox,
        new TransactionTemplate(transactionManager));
  }

  @Bean
  /* package */ GetTotpStatusUseCase getTotpStatusUseCase(
      final AccountRepository accounts,
      final AccountAuthenticationPolicyProvider policyProvider,
      final TotpCredentialRepository credentials,
      final TotpRecoveryCodeRepository recoveryCodes) {
    return new GetTotpStatusService(accounts, policyProvider, credentials, recoveryCodes);
  }

  @Bean
  /* package */ RequiresSecondFactorUseCase requiresSecondFactorUseCase(
      final AccountRepository accounts,
      final AccountAuthenticationPolicyProvider policyProvider,
      final TotpCredentialRepository credentials) {
    return new RequiresSecondFactorService(accounts, policyProvider, credentials);
  }
}
