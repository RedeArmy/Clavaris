package com.clavaris.identity.infrastructure.config;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.identity.application.usecase.authenticateplatformaccountwithpassword.AuthenticatePlatformAccountWithPasswordService;
import com.clavaris.identity.application.usecase.authenticateplatformaccountwithpassword.AuthenticatePlatformAccountWithPasswordUseCase;
import com.clavaris.identity.application.usecase.authenticatewithpassword.PasswordVerifier;
import com.clavaris.identity.application.usecase.confirmplatformaccountemailverification.ConfirmPlatformAccountEmailVerificationService;
import com.clavaris.identity.application.usecase.confirmplatformaccountemailverification.ConfirmPlatformAccountEmailVerificationUseCase;
import com.clavaris.identity.application.usecase.confirmplatformaccountpasswordreset.ConfirmPlatformAccountPasswordResetService;
import com.clavaris.identity.application.usecase.confirmplatformaccountpasswordreset.ConfirmPlatformAccountPasswordResetUseCase;
import com.clavaris.identity.application.usecase.confirmplatformaccountpasswordreset.PlatformAccountSessionRevoker;
import com.clavaris.identity.application.usecase.registeraccount.PasswordHasher;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.registerplatformaccount.RegisterPlatformAccountService;
import com.clavaris.identity.application.usecase.registerplatformaccount.RegisterPlatformAccountUseCase;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.PlatformMailSender;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.PlatformVerificationTokenRepository;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.RequestPlatformAccountEmailVerificationService;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.RequestPlatformAccountEmailVerificationUseCase;
import com.clavaris.identity.application.usecase.requestplatformaccountpasswordreset.RequestPlatformAccountPasswordResetService;
import com.clavaris.identity.application.usecase.requestplatformaccountpasswordreset.RequestPlatformAccountPasswordResetUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ADR-0012: wires {@code PlatformAccount}'s own use cases to Spring's context — split out from
 * {@link IdentityUseCaseConfig} (which carries its own {@code PMD.ExcessiveImports}/{@code
 * CouplingBetweenObjects} suppressions for the tenant-tier's own six use cases) purely to keep each
 * file a manageable size; both classes wire the same module's use cases, split by bounded concern
 * (tenant {@code Account} vs. platform {@code PlatformAccount}), not by anything architectural.
 * Smaller than that class — under PMD's ExcessiveImports/CouplingBetweenObjects thresholds, so no
 * class-level suppression needed here. AvoidDuplicateLiterals below: same "PMD.LongVariable" reused
 * across four parameters rationale as IdentityUseCaseConfig's own identical suppression.
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
@Configuration
class PlatformAccountUseCaseConfig {

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ PlatformAccountUseCaseConfig() {
    // Intentionally empty — this class holds no state, only the @Bean methods below.
  }

  @Bean
  /* package */ RegisterPlatformAccountUseCase registerPlatformAccountUseCase(
      final PlatformAccountRepository accounts, final PasswordHasher passwordHasher) {
    return new RegisterPlatformAccountService(accounts, passwordHasher);
  }

  @Bean
  /* package */ AuthenticatePlatformAccountWithPasswordUseCase
      authenticatePlatformAccountWithPasswordUseCase(
          final PlatformAccountRepository accounts,
          final PasswordVerifier passwordVerifier,
          final SecurityMetricsRecorder securityMetrics) {
    return new AuthenticatePlatformAccountWithPasswordService(
        accounts, passwordVerifier, securityMetrics);
  }

  @Bean
  /* package */ RequestPlatformAccountEmailVerificationUseCase
      requestPlatformAccountEmailVerificationUseCase(
          final PlatformAccountRepository accounts,
          @SuppressWarnings("PMD.LongVariable")
              final PlatformVerificationTokenRepository verificationTokens,
          final PlatformMailSender mailSender) {
    return new RequestPlatformAccountEmailVerificationService(
        accounts, verificationTokens, mailSender);
  }

  @Bean
  /* package */ ConfirmPlatformAccountEmailVerificationUseCase
      confirmPlatformAccountEmailVerificationUseCase(
          @SuppressWarnings("PMD.LongVariable")
              final PlatformVerificationTokenRepository verificationTokens,
          final PlatformAccountRepository accounts) {
    return new ConfirmPlatformAccountEmailVerificationService(verificationTokens, accounts);
  }

  @Bean
  /* package */ RequestPlatformAccountPasswordResetUseCase
      requestPlatformAccountPasswordResetUseCase(
          final PlatformAccountRepository accounts,
          @SuppressWarnings("PMD.LongVariable")
              final PlatformVerificationTokenRepository verificationTokens,
          final PlatformMailSender mailSender) {
    return new RequestPlatformAccountPasswordResetService(accounts, verificationTokens, mailSender);
  }

  @Bean
  /* package */ ConfirmPlatformAccountPasswordResetUseCase
      confirmPlatformAccountPasswordResetUseCase(
          @SuppressWarnings("PMD.LongVariable")
              final PlatformVerificationTokenRepository verificationTokens,
          final PlatformAccountRepository accounts,
          final PlatformAccountSessionRevoker sessionRevoker,
          final PasswordHasher passwordHasher) {
    return new ConfirmPlatformAccountPasswordResetService(
        verificationTokens, accounts, sessionRevoker, passwordHasher);
  }
}
