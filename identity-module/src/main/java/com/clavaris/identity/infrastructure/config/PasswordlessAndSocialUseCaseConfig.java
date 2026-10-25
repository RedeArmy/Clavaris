package com.clavaris.identity.infrastructure.config;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.identity.application.usecase.authenticatewithemailcode.AuthenticateWithEmailCodeService;
import com.clavaris.identity.application.usecase.authenticatewithemailcode.AuthenticateWithEmailCodeUseCase;
import com.clavaris.identity.application.usecase.authenticatewithemaillink.AuthenticateWithEmailLinkService;
import com.clavaris.identity.application.usecase.authenticatewithemaillink.AuthenticateWithEmailLinkUseCase;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.AuthenticateWithSocialProviderService;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.AuthenticateWithSocialProviderUseCase;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.OrganizationSocialLoginPolicyProvider;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.PendingSocialLinkRepository;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.SocialIdentityRepository;
import com.clavaris.identity.application.usecase.confirmpendingsociallink.ConfirmPendingSocialLinkService;
import com.clavaris.identity.application.usecase.confirmpendingsociallink.ConfirmPendingSocialLinkUseCase;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.registeraccount.PasswordHasher;
import com.clavaris.identity.application.usecase.requestemailsignincode.RequestEmailSignInCodeService;
import com.clavaris.identity.application.usecase.requestemailsignincode.RequestEmailSignInCodeUseCase;
import com.clavaris.identity.application.usecase.requestemailsigninlink.RequestEmailSignInLinkService;
import com.clavaris.identity.application.usecase.requestemailsigninlink.RequestEmailSignInLinkUseCase;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the identity use cases for: passwordless sign-in (email code and link) and social login.
 *
 * <p>Deliberately kept out of {@code application/usecase} itself: a service's only Spring
 * dependency is {@code @Transactional} on its {@code handle} method
 * (first-vertical-slice-blueprint.md §2.3), so bean registration lives here, in infrastructure.
 * Split from one 868-line {@code IdentityUseCaseConfig} by area; beans reach each other through
 * ordinary constructor injection, so the split changes no wiring.
 */
// PMD.LongVariable: ports are named for what they are (verificationTokens, accountTokenRevoker), as
// in the services they are handed to.
@SuppressWarnings("PMD.LongVariable")
@Configuration
class PasswordlessAndSocialUseCaseConfig {

  // Written out explicitly for the same reason as Argon2PasswordHasher's own constructor
  // (see its comment) — only Spring's own component scan ever needs to instantiate this class.
  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ PasswordlessAndSocialUseCaseConfig() {
    // Intentionally empty — this class holds no state, only @Bean methods.
  }

  // ADR-0024 §3 — passwordless email sign-in
  @Bean
  /* package */ RequestEmailSignInCodeUseCase requestEmailSignInCodeUseCase(
      final AccountRepository accounts,
      final VerificationTokenRepository verificationTokens,
      final MailSender mailSender,
      final AccountAuthenticationPolicyProvider policyProvider) {
    return new RequestEmailSignInCodeService(
        accounts, verificationTokens, mailSender, policyProvider);
  }

  @Bean
  /* package */ AuthenticateWithEmailCodeUseCase authenticateWithEmailCodeUseCase(
      final AccountRepository accounts, final VerificationTokenRepository verificationTokens) {
    return new AuthenticateWithEmailCodeService(accounts, verificationTokens);
  }

  @Bean
  /* package */ RequestEmailSignInLinkUseCase requestEmailSignInLinkUseCase(
      final AccountRepository accounts,
      final VerificationTokenRepository verificationTokens,
      final MailSender mailSender,
      final AccountAuthenticationPolicyProvider policyProvider) {
    return new RequestEmailSignInLinkService(
        accounts, verificationTokens, mailSender, policyProvider);
  }

  @Bean
  /* package */ AuthenticateWithEmailLinkUseCase authenticateWithEmailLinkUseCase(
      final AccountRepository accounts, final VerificationTokenRepository verificationTokens) {
    return new AuthenticateWithEmailLinkService(accounts, verificationTokens);
  }

  // ADR-0020 Decision 1: needs its own TransactionTemplate (not @Transactional on handle()) for
  // exactly the reason AddWorkspaceMemberUseCase's own @Bean method already documents — one branch
  // of this flow needs an atomic multi-write, another needs a write followed by a real mail send.
  // TD-FUT-019's own new accountAuthenticationPolicyProvider parameter pushed this to the
  // PMD.ExcessiveParameterList threshold too — same "wiring, not sprawl" reasoning.
  @SuppressWarnings({"java:S107", "PMD.ExcessiveParameterList"})
  @Bean
  /* package */ AuthenticateWithSocialProviderUseCase authenticateWithSocialProviderUseCase(
      final AccountRepository accounts,
      final SocialIdentityRepository socialIdentities,
      final PendingSocialLinkRepository pendingLinks,
      final OrganizationSocialLoginPolicyProvider policyProvider,
      final MailSender mailSender,
      final EventOutboxWriter eventOutboxWriter,
      final SecurityMetricsRecorder securityMetrics,
      final PlatformTransactionManager transactionManager,
      final PasswordHasher hasher,
      final AccountAuthenticationPolicyProvider accountAuthenticationPolicyProvider) {
    return new AuthenticateWithSocialProviderService(
        accounts,
        socialIdentities,
        pendingLinks,
        policyProvider,
        mailSender,
        eventOutboxWriter,
        securityMetrics,
        new TransactionTemplate(transactionManager),
        hasher,
        accountAuthenticationPolicyProvider);
  }

  @Bean
  /* package */ ConfirmPendingSocialLinkUseCase confirmPendingSocialLinkUseCase(
      final PendingSocialLinkRepository pendingLinks,
      final SocialIdentityRepository socialIdentities,
      final AccountRepository accounts,
      final EventOutboxWriter eventOutboxWriter) {
    return new ConfirmPendingSocialLinkService(
        pendingLinks, socialIdentities, accounts, eventOutboxWriter);
  }
}
