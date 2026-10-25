package com.clavaris.identity.infrastructure.config;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.admincreateaccountfororganization.AdminCreateAccountForOrganizationService;
import com.clavaris.identity.application.usecase.admincreateaccountfororganization.AdminCreateAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.approveaccountregistration.ApproveAccountRegistrationService;
import com.clavaris.identity.application.usecase.approveaccountregistration.ApproveAccountRegistrationUseCase;
import com.clavaris.identity.application.usecase.deleteaccount.DeleteAccountService;
import com.clavaris.identity.application.usecase.deleteaccount.DeleteAccountUseCase;
import com.clavaris.identity.application.usecase.deleteaccount.WorkspaceMembershipEraser;
import com.clavaris.identity.application.usecase.deleteownaccount.DeleteOwnAccountService;
import com.clavaris.identity.application.usecase.deleteownaccount.DeleteOwnAccountUseCase;
import com.clavaris.identity.application.usecase.registeraccount.AccessRestrictionPolicyProvider;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.BreachedPasswordChecker;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.registeraccount.PasswordHasher;
import com.clavaris.identity.application.usecase.registeraccount.RegisterAccountService;
import com.clavaris.identity.application.usecase.registeraccount.RegisterAccountUseCase;
import com.clavaris.identity.application.usecase.rejectaccountregistration.RejectAccountRegistrationService;
import com.clavaris.identity.application.usecase.rejectaccountregistration.RejectAccountRegistrationUseCase;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountSessionRevoker;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountTokenRevoker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the identity use cases for: account registration, deletion and approval.
 *
 * <p>Deliberately kept out of {@code application/usecase} itself: a service's only Spring
 * dependency is {@code @Transactional} on its {@code handle} method
 * (first-vertical-slice-blueprint.md §2.3), so bean registration lives here, in infrastructure. The
 * identity module's wiring is split by area (see the sibling {@code *UseCaseConfig} classes); beans
 * reach each other through ordinary constructor injection, so the split changes no wiring.
 */
// PMD.LongVariable: ports are named for what they are (verificationTokens, accountTokenRevoker), as
// in the services they are handed to.
@SuppressWarnings("PMD.LongVariable")
@Configuration
class IdentityUseCaseConfig {

  // Written out explicitly for the same reason as Argon2PasswordHasher's own constructor
  // (see its comment) — only Spring's own component scan ever needs to instantiate this class.
  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ IdentityUseCaseConfig() {
    // Intentionally empty — this class holds no state, only @Bean methods.
  }

  // This bean-definition method has no reason to be called directly by anything outside Spring's
  // own container wiring — RegisterAccountUseCase (the interface) is what every other caller,
  // including other modules, should depend on.
  @Bean
  /* package */ RegisterAccountUseCase registerAccountUseCase(
      final AccountRepository accountRepository,
      final PasswordHasher passwordHasher,
      final EventOutboxWriter eventOutboxWriter,
      final AccountAuthenticationPolicyProvider policyProvider,
      final AccessRestrictionPolicyProvider accessRestrictionPolicyProvider,
      final BreachedPasswordChecker breachedPasswordChecker) {
    return new RegisterAccountService(
        accountRepository,
        passwordHasher,
        eventOutboxWriter,
        policyProvider,
        accessRestrictionPolicyProvider,
        breachedPasswordChecker);
  }

  // SDE-III review, 2026-09-19 — Clerk dashboard "Users" tab parity.
  @Bean
  /* package */ AdminCreateAccountForOrganizationUseCase adminCreateAccountForOrganizationUseCase(
      final AccountRepository accountRepository,
      final PasswordHasher passwordHasher,
      final AccessRestrictionPolicyProvider accessRestrictionPolicyProvider,
      final BreachedPasswordChecker breachedPasswordChecker) {
    return new AdminCreateAccountForOrganizationService(
        accountRepository,
        passwordHasher,
        accessRestrictionPolicyProvider,
        breachedPasswordChecker);
  }

  @Bean
  /* package */ DeleteAccountUseCase deleteAccountUseCase(
      final AccountRepository accounts,
      final AccountTokenRevoker accountTokenRevoker,
      final AccountSessionRevoker accountSessionRevoker,
      final WorkspaceMembershipEraser workspaceMembershipEraser,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter eventOutboxWriter) {
    return new DeleteAccountService(
        accounts,
        accountTokenRevoker,
        accountSessionRevoker,
        workspaceMembershipEraser,
        auditEvents,
        eventOutboxWriter);
  }

  @Bean
  /* package */ DeleteOwnAccountUseCase deleteOwnAccountUseCase(
      final AccountRepository accounts, final DeleteAccountUseCase deleteAccount) {
    return new DeleteOwnAccountService(accounts, deleteAccount);
  }

  // TD-FUT-019: approve/reject a PENDING_APPROVAL self-registration.
  @Bean
  /* package */ ApproveAccountRegistrationUseCase approveAccountRegistrationUseCase(
      final AccountRepository accounts,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter eventOutboxWriter) {
    return new ApproveAccountRegistrationService(accounts, auditEvents, eventOutboxWriter);
  }

  // TD-FUT-019: approve/reject a PENDING_APPROVAL self-registration.
  @Bean
  /* package */ RejectAccountRegistrationUseCase rejectAccountRegistrationUseCase(
      final AccountRepository accounts,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter eventOutboxWriter) {
    return new RejectAccountRegistrationService(accounts, auditEvents, eventOutboxWriter);
  }
}
