package com.clavaris.identity.infrastructure.config;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.identity.application.usecase.activateplatformsigningkey.ActivatePlatformSigningKeyService;
import com.clavaris.identity.application.usecase.activateplatformsigningkey.ActivatePlatformSigningKeyUseCase;
import com.clavaris.identity.application.usecase.activateplatformsigningkey.PlatformSigningKeyRepository;
import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.ActivateSigningKeyForOrganizationService;
import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.ActivateSigningKeyForOrganizationUseCase;
import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.SigningKeyRepository;
import com.clavaris.identity.application.usecase.authenticatewithpassword.AuthenticateWithPasswordService;
import com.clavaris.identity.application.usecase.authenticatewithpassword.AuthenticateWithPasswordUseCase;
import com.clavaris.identity.application.usecase.authenticatewithpassword.PasswordVerifier;
import com.clavaris.identity.application.usecase.confirmemailverification.ConfirmEmailVerificationService;
import com.clavaris.identity.application.usecase.confirmemailverification.ConfirmEmailVerificationUseCase;
import com.clavaris.identity.application.usecase.confirmpasswordreset.ConfirmPasswordResetService;
import com.clavaris.identity.application.usecase.confirmpasswordreset.ConfirmPasswordResetUseCase;
import com.clavaris.identity.application.usecase.deleteaccount.DeleteAccountService;
import com.clavaris.identity.application.usecase.deleteaccount.DeleteAccountUseCase;
import com.clavaris.identity.application.usecase.issuerefreshtoken.IssueRefreshTokenService;
import com.clavaris.identity.application.usecase.issuerefreshtoken.IssueRefreshTokenUseCase;
import com.clavaris.identity.application.usecase.issuerefreshtoken.RefreshTokenRepository;
import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.registeraccount.PasswordHasher;
import com.clavaris.identity.application.usecase.registeraccount.RegisterAccountService;
import com.clavaris.identity.application.usecase.registeraccount.RegisterAccountUseCase;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.application.usecase.requestemailverification.RequestEmailVerificationService;
import com.clavaris.identity.application.usecase.requestemailverification.RequestEmailVerificationUseCase;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.application.usecase.requestpasswordreset.RequestPasswordResetService;
import com.clavaris.identity.application.usecase.requestpasswordreset.RequestPasswordResetUseCase;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountSessionRevoker;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountTokenRevoker;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.RotateRefreshTokenService;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.RotateRefreshTokenUseCase;
import com.clavaris.identity.application.usecase.rotatesigningkeyfororganization.RotateSigningKeyForOrganizationService;
import com.clavaris.identity.application.usecase.rotatesigningkeyfororganization.RotateSigningKeyForOrganizationUseCase;
import com.clavaris.identity.application.usecase.rotatesigningkeyfororganization.SigningKeyMaterialGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires application-layer use cases to Spring's context. Deliberately kept out of {@code
 * application/usecase/registeraccount} itself: {@code RegisterAccountService}'s only Spring
 * dependency is the {@code @Transactional} annotation on its {@code handle} method
 * (first-vertical-slice-blueprint.md §2.3) — bean registration for it lives here, in
 * infrastructure, not as a class-level {@code @Service} on the service itself.
 */
// ExcessiveImports/CouplingBetweenObjects: this class exists specifically to wire together every
// use case in the module — one import per port/service/use-case type is what that job looks like,
// not accidental coupling to split apart. Same reasoning as app module's own AuthorizationServer
// config classes' identical suppression for "wiring together many distinct types." AvoidDuplicate
// Literals: the repeated string is "PMD.LongVariable" itself, used on six different parameters
// across five methods — a real code smell would be a repeated business-logic string, not the same
// suppression annotation value reused because the same port name (verificationTokens,
// accountTokenRevoker) legitimately appears as a parameter that many times. TooManyMethods: one
// @Bean method per use case is what this class's entire job looks like — deleteAccountUseCase
// tipped this over PMD's default threshold, not a sign this class grew organically past its own
// single responsibility.
@SuppressWarnings({
  "PMD.ExcessiveImports",
  "PMD.CouplingBetweenObjects",
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyMethods"
})
@Configuration
class IdentityUseCaseConfig {

  // Written out explicitly for the same reason as Argon2PasswordHasher's own constructor
  // (see its comment) — only Spring's own component scan ever needs to instantiate this class.
  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ IdentityUseCaseConfig() {
    // Intentionally empty — this class holds no state, only the @Bean method below.
  }

  // This bean-definition method has no reason to be called directly by anything outside Spring's
  // own container wiring — RegisterAccountUseCase (the interface) is what every other caller,
  // including other modules, should depend on.
  @Bean
  /* package */ RegisterAccountUseCase registerAccountUseCase(
      final AccountRepository accountRepository,
      final PasswordHasher passwordHasher,
      final EventOutboxWriter eventOutboxWriter) {
    return new RegisterAccountService(accountRepository, passwordHasher, eventOutboxWriter);
  }

  @Bean
  /* package */ ActivatePlatformSigningKeyUseCase activatePlatformSigningKeyUseCase(
      final PlatformSigningKeyRepository platformKeys) {
    return new ActivatePlatformSigningKeyService(platformKeys);
  }

  @Bean
  /* package */ ActivateSigningKeyForOrganizationUseCase activateSigningKeyForOrganizationUseCase(
      final SigningKeyRepository signingKeys) {
    return new ActivateSigningKeyForOrganizationService(signingKeys);
  }

  // TD-SEC-008: OrganizationSigningKeyMaterialFactory (infrastructure) already implements
  // SigningKeyMaterialGenerator directly — Spring resolves this parameter to that same bean, no
  // separate bridge class needed (unlike the cross-module CreateOrganizationSigningKeyBridge).
  @Bean
  /* package */ RotateSigningKeyForOrganizationUseCase rotateSigningKeyForOrganizationUseCase(
      final SigningKeyRepository signingKeys,
      final SigningKeyMaterialGenerator keyMaterial,
      @SuppressWarnings("PMD.LongVariable")
          final ActivateSigningKeyForOrganizationUseCase activateSigningKeyForOrganizationUseCase,
      final AuditEventRecorder auditEvents) {
    return new RotateSigningKeyForOrganizationService(
        signingKeys, keyMaterial, activateSigningKeyForOrganizationUseCase, auditEvents);
  }

  @Bean
  /* package */ AuthenticateWithPasswordUseCase authenticateWithPasswordUseCase(
      final AccountRepository accountRepository,
      final PasswordVerifier passwordVerifier,
      final SecurityMetricsRecorder securityMetrics) {
    return new AuthenticateWithPasswordService(
        accountRepository, passwordVerifier, securityMetrics);
  }

  @Bean
  /* package */ IssueRefreshTokenUseCase issueRefreshTokenUseCase(
      final SessionRepository sessions,
      final RefreshTokenRepository refreshTokens,
      final SecurityMetricsRecorder securityMetrics) {
    return new IssueRefreshTokenService(sessions, refreshTokens, securityMetrics);
  }

  @Bean
  /* package */ RotateRefreshTokenUseCase rotateRefreshTokenUseCase(
      final RefreshTokenRepository refreshTokens,
      final SessionRepository sessions,
      final AccountRepository accounts,
      @SuppressWarnings("PMD.LongVariable") final AccountTokenRevoker accountTokenRevoker,
      @SuppressWarnings("PMD.LongVariable") final AccountSessionRevoker accountSessionRevoker,
      final EventOutboxWriter eventOutboxWriter,
      final SecurityMetricsRecorder securityMetrics) {
    return new RotateRefreshTokenService(
        refreshTokens,
        sessions,
        accounts,
        accountTokenRevoker,
        accountSessionRevoker,
        eventOutboxWriter,
        securityMetrics);
  }

  @Bean
  /* package */ RequestEmailVerificationUseCase requestEmailVerificationUseCase(
      final AccountRepository accounts,
      @SuppressWarnings("PMD.LongVariable") final VerificationTokenRepository verificationTokens,
      final MailSender mailSender) {
    return new RequestEmailVerificationService(accounts, verificationTokens, mailSender);
  }

  @Bean
  /* package */ ConfirmEmailVerificationUseCase confirmEmailVerificationUseCase(
      @SuppressWarnings("PMD.LongVariable") final VerificationTokenRepository verificationTokens,
      final AccountRepository accounts,
      final EventOutboxWriter eventOutboxWriter) {
    return new ConfirmEmailVerificationService(verificationTokens, accounts, eventOutboxWriter);
  }

  @Bean
  /* package */ RequestPasswordResetUseCase requestPasswordResetUseCase(
      final AccountRepository accounts,
      @SuppressWarnings("PMD.LongVariable") final VerificationTokenRepository verificationTokens,
      final MailSender mailSender,
      final EventOutboxWriter eventOutboxWriter) {
    return new RequestPasswordResetService(
        accounts, verificationTokens, mailSender, eventOutboxWriter);
  }

  @Bean
  /* package */ ConfirmPasswordResetUseCase confirmPasswordResetUseCase(
      @SuppressWarnings("PMD.LongVariable") final VerificationTokenRepository verificationTokens,
      final AccountRepository accounts,
      final SessionRepository sessions,
      final RefreshTokenRepository refreshTokens,
      @SuppressWarnings("PMD.LongVariable") final AccountTokenRevoker accountTokenRevoker,
      @SuppressWarnings("PMD.LongVariable") final AccountSessionRevoker accountSessionRevoker,
      final PasswordHasher passwordHasher,
      final EventOutboxWriter eventOutboxWriter) {
    return new ConfirmPasswordResetService(
        verificationTokens,
        accounts,
        sessions,
        refreshTokens,
        accountTokenRevoker,
        accountSessionRevoker,
        passwordHasher,
        eventOutboxWriter);
  }

  @Bean
  /* package */ DeleteAccountUseCase deleteAccountUseCase(
      final AccountRepository accounts,
      @SuppressWarnings("PMD.LongVariable") final AccountTokenRevoker accountTokenRevoker,
      @SuppressWarnings("PMD.LongVariable") final AccountSessionRevoker accountSessionRevoker,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter eventOutboxWriter) {
    return new DeleteAccountService(
        accounts, accountTokenRevoker, accountSessionRevoker, auditEvents, eventOutboxWriter);
  }
}
