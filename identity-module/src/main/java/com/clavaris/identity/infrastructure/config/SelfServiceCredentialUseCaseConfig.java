package com.clavaris.identity.infrastructure.config;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.authenticatewithpassword.PasswordVerifier;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.OrganizationSocialLoginPolicyProvider;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.SocialIdentityRepository;
import com.clavaris.identity.application.usecase.changepassword.ChangePasswordService;
import com.clavaris.identity.application.usecase.changepassword.ChangePasswordUseCase;
import com.clavaris.identity.application.usecase.confirmemailchange.ConfirmEmailChangeService;
import com.clavaris.identity.application.usecase.confirmemailchange.ConfirmEmailChangeUseCase;
import com.clavaris.identity.application.usecase.connectsocialidentity.ConnectSocialIdentityService;
import com.clavaris.identity.application.usecase.connectsocialidentity.ConnectSocialIdentityUseCase;
import com.clavaris.identity.application.usecase.disconnectsocialidentity.DisconnectSocialIdentityService;
import com.clavaris.identity.application.usecase.disconnectsocialidentity.DisconnectSocialIdentityUseCase;
import com.clavaris.identity.application.usecase.issuerefreshtoken.RefreshTokenRepository;
import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionRepository;
import com.clavaris.identity.application.usecase.listactivesessionsforaccount.AccountActiveSessionsRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.BreachedPasswordChecker;
import com.clavaris.identity.application.usecase.registeraccount.PasswordHasher;
import com.clavaris.identity.application.usecase.registerwebauthncredential.WebAuthnCredentialRepository;
import com.clavaris.identity.application.usecase.requestemailchange.EmailChangeRequestRepository;
import com.clavaris.identity.application.usecase.requestemailchange.RequestEmailChangeService;
import com.clavaris.identity.application.usecase.requestemailchange.RequestEmailChangeUseCase;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountTokenRevoker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
      final AuditEventRecorder auditEvents,
      final MailSender mailSender) {
    return new ChangePasswordService(
        accounts,
        verifier,
        hasher,
        breachedPasswordChecker,
        sessions,
        refreshTokens,
        accountTokenRevoker,
        activeSessions,
        auditEvents,
        mailSender);
  }

  @Bean
  /* package */ RequestEmailChangeUseCase requestEmailChangeUseCase(
      final AccountRepository accounts,
      final PasswordVerifier verifier,
      final EmailChangeRequestRepository requests,
      final MailSender mailSender,
      final AuditEventRecorder auditEvents) {
    return new RequestEmailChangeService(accounts, verifier, requests, mailSender, auditEvents);
  }

  @Bean
  /* package */ ConfirmEmailChangeUseCase confirmEmailChangeUseCase(
      final EmailChangeRequestRepository requests,
      final AccountRepository accounts,
      final AuditEventRecorder auditEvents,
      final MailSender mailSender) {
    return new ConfirmEmailChangeService(requests, accounts, auditEvents, mailSender);
  }

  @Bean
  /* package */ DisconnectSocialIdentityUseCase disconnectSocialIdentityUseCase(
      final AccountRepository accounts,
      final SocialIdentityRepository identities,
      final WebAuthnCredentialRepository passkeys,
      final AuditEventRecorder auditEvents) {
    return new DisconnectSocialIdentityService(accounts, identities, passkeys, auditEvents);
  }

  @Bean
  /* package */ ConnectSocialIdentityUseCase connectSocialIdentityUseCase(
      final AccountRepository accounts,
      final SocialIdentityRepository identities,
      final OrganizationSocialLoginPolicyProvider policy,
      final AuditEventRecorder auditEvents) {
    return new ConnectSocialIdentityService(accounts, identities, policy, auditEvents);
  }
}
