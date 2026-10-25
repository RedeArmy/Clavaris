package com.clavaris.identity.infrastructure.config;

import com.clavaris.common.application.port.AuditEventReader;
import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.banaccount.BanAccountService;
import com.clavaris.identity.application.usecase.banaccount.BanAccountUseCase;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationService;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.getaccountprofile.GetAccountProfileService;
import com.clavaris.identity.application.usecase.getaccountprofile.GetAccountProfileUseCase;
import com.clavaris.identity.application.usecase.getauditlogforaccount.GetAuditLogForAccountService;
import com.clavaris.identity.application.usecase.getauditlogforaccount.GetAuditLogForAccountUseCase;
import com.clavaris.identity.application.usecase.impersonateaccount.ImpersonateAccountService;
import com.clavaris.identity.application.usecase.impersonateaccount.ImpersonateAccountUseCase;
import com.clavaris.identity.application.usecase.issuerefreshtoken.RefreshTokenRepository;
import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionRepository;
import com.clavaris.identity.application.usecase.listaccountsfororganization.ListAccountsForOrganizationService;
import com.clavaris.identity.application.usecase.listaccountsfororganization.ListAccountsForOrganizationUseCase;
import com.clavaris.identity.application.usecase.reactivateaccount.ReactivateAccountService;
import com.clavaris.identity.application.usecase.reactivateaccount.ReactivateAccountUseCase;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountSessionRevoker;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountTokenRevoker;
import com.clavaris.identity.application.usecase.suspendaccount.SuspendAccountService;
import com.clavaris.identity.application.usecase.suspendaccount.SuspendAccountUseCase;
import com.clavaris.identity.application.usecase.unbanaccount.UnbanAccountService;
import com.clavaris.identity.application.usecase.unbanaccount.UnbanAccountUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the identity use cases for: account lookup and operator administration: listing, audit log,
 * impersonation, suspension and bans.
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
class AccountAdministrationUseCaseConfig {

  // Written out explicitly for the same reason as Argon2PasswordHasher's own constructor
  // (see its comment) — only Spring's own component scan ever needs to instantiate this class.
  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ AccountAdministrationUseCaseConfig() {
    // Intentionally empty — this class holds no state, only @Bean methods.
  }

  @Bean
  /* package */ ListAccountsForOrganizationUseCase listAccountsForOrganizationUseCase(
      final AccountRepository accountRepository) {
    return new ListAccountsForOrganizationService(accountRepository);
  }

  // SDE-III review, 2026-09-19 — Clerk dashboard "Users" tab "View Profile" menu item.
  @Bean
  /* package */ GetAccountForOrganizationUseCase getAccountForOrganizationUseCase(
      final AccountRepository accountRepository) {
    return new GetAccountForOrganizationService(accountRepository);
  }

  // TD-FUT-041: Backend-API-style profile read, accountId-only (no organizationId filter — see
  // that query's own Javadoc for why this is a separate, simpler use case from the one right
  // above).
  @Bean
  /* package */ GetAccountProfileUseCase getAccountProfileUseCase(
      final AccountRepository accountRepository) {
    return new GetAccountProfileService(accountRepository);
  }

  @Bean
  /* package */ SuspendAccountUseCase suspendAccountUseCase(
      final AccountRepository accounts,
      final SessionRepository sessions,
      final RefreshTokenRepository refreshTokens,
      final AccountTokenRevoker accountTokenRevoker,
      final AccountSessionRevoker accountSessionRevoker,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter eventOutboxWriter) {
    return new SuspendAccountService(
        accounts,
        sessions,
        refreshTokens,
        accountTokenRevoker,
        accountSessionRevoker,
        auditEvents,
        eventOutboxWriter);
  }

  // SDE-III review, 2026-09-19 — Clerk dashboard "Users" tab "View log" menu item.
  @Bean
  /* package */ GetAuditLogForAccountUseCase getAuditLogForAccountUseCase(
      final AuditEventReader auditEvents) {
    return new GetAuditLogForAccountService(auditEvents);
  }

  // SDE-III feature build, 2026-09-03: support/operator impersonation — see
  // ImpersonateAccountCommand's own Javadoc for why this use case only validates+audits, never
  // mints anything itself.
  @Bean
  /* package */ ImpersonateAccountUseCase impersonateAccountUseCase(
      final AccountRepository accounts, final AuditEventRecorder auditEvents) {
    return new ImpersonateAccountService(accounts, auditEvents);
  }

  @Bean
  /* package */ ReactivateAccountUseCase reactivateAccountUseCase(
      final AccountRepository accounts,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter eventOutboxWriter) {
    return new ReactivateAccountService(accounts, auditEvents, eventOutboxWriter);
  }

  // SDE-III review, 2026-09-19 — Clerk dashboard "Users" parity: same shape as
  // suspendAccountUseCase above, a deliberately separate bean/use case, not a reuse.
  @Bean
  /* package */ BanAccountUseCase banAccountUseCase(
      final AccountRepository accounts,
      final SessionRepository sessions,
      final RefreshTokenRepository refreshTokens,
      final AccountTokenRevoker accountTokenRevoker,
      final AccountSessionRevoker accountSessionRevoker,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter eventOutboxWriter) {
    return new BanAccountService(
        accounts,
        sessions,
        refreshTokens,
        accountTokenRevoker,
        accountSessionRevoker,
        auditEvents,
        eventOutboxWriter);
  }

  @Bean
  /* package */ UnbanAccountUseCase unbanAccountUseCase(
      final AccountRepository accounts,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter eventOutboxWriter) {
    return new UnbanAccountService(accounts, auditEvents, eventOutboxWriter);
  }
}
