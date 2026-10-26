package com.clavaris.identity.infrastructure.config;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.identity.application.usecase.authenticatewithpassword.AuthenticateWithPasswordService;
import com.clavaris.identity.application.usecase.authenticatewithpassword.AuthenticateWithPasswordUseCase;
import com.clavaris.identity.application.usecase.authenticatewithpassword.PasswordVerifier;
import com.clavaris.identity.application.usecase.authenticatewithusername.AuthenticateWithUsernameService;
import com.clavaris.identity.application.usecase.authenticatewithusername.AuthenticateWithUsernameUseCase;
import com.clavaris.identity.application.usecase.confirmdevicetrustchallenge.ConfirmDeviceTrustChallengeService;
import com.clavaris.identity.application.usecase.confirmdevicetrustchallenge.ConfirmDeviceTrustChallengeUseCase;
import com.clavaris.identity.application.usecase.issuerefreshtoken.IssueRefreshTokenService;
import com.clavaris.identity.application.usecase.issuerefreshtoken.IssueRefreshTokenUseCase;
import com.clavaris.identity.application.usecase.issuerefreshtoken.RefreshTokenRepository;
import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionPolicyProvider;
import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionRepository;
import com.clavaris.identity.application.usecase.recordsessionoidcid.RecordSessionOidcIdService;
import com.clavaris.identity.application.usecase.recordsessionoidcid.RecordSessionOidcIdUseCase;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.requestdevicetrustchallenge.RequestDeviceTrustChallengeService;
import com.clavaris.identity.application.usecase.requestdevicetrustchallenge.RequestDeviceTrustChallengeUseCase;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountSessionRevoker;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountTokenRevoker;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.RotateRefreshTokenService;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.RotateRefreshTokenUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the identity use cases for: sign-in with a password or username, device trust and refresh
 * tokens.
 *
 * <p>Deliberately kept out of {@code application/usecase} itself: a service's only Spring
 * dependency is {@code @Transactional} on its {@code handle} method
 * (first-vertical-slice-blueprint.md §2.3), so bean registration lives here, in infrastructure.
 * Split from one 868-line {@code IdentityUseCaseConfig} by area; beans reach each other through
 * ordinary constructor injection, so the split changes no wiring.
 */
@Configuration
class AuthenticationUseCaseConfig {

  // Written out explicitly for the same reason as Argon2PasswordHasher's own constructor
  // (see its comment) — only Spring's own component scan ever needs to instantiate this class.
  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ AuthenticationUseCaseConfig() {
    // Intentionally empty — this class holds no state, only @Bean methods.
  }

  @Bean
  /* package */ AuthenticateWithPasswordUseCase authenticateWithPasswordUseCase(
      final AccountRepository accountRepository,
      final PasswordVerifier passwordVerifier,
      final SecurityMetricsRecorder securityMetrics,
      final AccountAuthenticationPolicyProvider policyProvider) {
    return new AuthenticateWithPasswordService(
        accountRepository, passwordVerifier, securityMetrics, policyProvider);
  }

  @Bean
  /* package */ RecordSessionOidcIdUseCase recordSessionOidcIdUseCase(
      final SessionRepository sessions) {
    return new RecordSessionOidcIdService(sessions);
  }

  @Bean
  /* package */ IssueRefreshTokenUseCase issueRefreshTokenUseCase(
      final SessionRepository sessions,
      final RefreshTokenRepository refreshTokens,
      final SecurityMetricsRecorder securityMetrics,
      final AccountRepository accounts,
      final SessionPolicyProvider sessionPolicyProvider,
      final AccountTokenRevoker accountTokenRevoker) {
    return new IssueRefreshTokenService(
        sessions,
        refreshTokens,
        securityMetrics,
        accounts,
        sessionPolicyProvider,
        accountTokenRevoker);
  }

  @Bean
  /* package */ RotateRefreshTokenUseCase rotateRefreshTokenUseCase(
      final RefreshTokenRepository refreshTokens,
      final SessionRepository sessions,
      final AccountRepository accounts,
      final AccountTokenRevoker accountTokenRevoker,
      final AccountSessionRevoker accountSessionRevoker,
      final EventOutboxWriter eventOutboxWriter,
      final SecurityMetricsRecorder securityMetrics,
      final SessionPolicyProvider sessionPolicyProvider) {
    return new RotateRefreshTokenService(
        refreshTokens,
        sessions,
        accounts,
        accountTokenRevoker,
        accountSessionRevoker,
        eventOutboxWriter,
        securityMetrics,
        sessionPolicyProvider);
  }

  // ADR-0024 §4 — username sign-in
  @Bean
  /* package */ AuthenticateWithUsernameUseCase authenticateWithUsernameUseCase(
      final AccountRepository accountRepository,
      final PasswordVerifier passwordVerifier,
      final AccountAuthenticationPolicyProvider policyProvider) {
    return new AuthenticateWithUsernameService(accountRepository, passwordVerifier, policyProvider);
  }

  // ADR-0024 §6 — device trust step-up challenge
  @Bean
  /* package */ RequestDeviceTrustChallengeUseCase requestDeviceTrustChallengeUseCase(
      final AccountRepository accounts,
      final VerificationTokenRepository verificationTokens,
      final MailSender mailSender) {
    return new RequestDeviceTrustChallengeService(accounts, verificationTokens, mailSender);
  }

  @Bean
  /* package */ ConfirmDeviceTrustChallengeUseCase confirmDeviceTrustChallengeUseCase(
      final VerificationTokenRepository verificationTokens) {
    return new ConfirmDeviceTrustChallengeService(verificationTokens);
  }
}
