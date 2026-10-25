package com.clavaris.identity.infrastructure.config;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.listactivesessionsforaccount.AccountActiveSessionsRepository;
import com.clavaris.identity.application.usecase.listactivesessionsforaccount.ListActiveSessionsForAccountService;
import com.clavaris.identity.application.usecase.listactivesessionsforaccount.ListActiveSessionsForAccountUseCase;
import com.clavaris.identity.application.usecase.listoauthgrantsforaccount.ListOAuthGrantsForAccountService;
import com.clavaris.identity.application.usecase.listoauthgrantsforaccount.ListOAuthGrantsForAccountUseCase;
import com.clavaris.identity.application.usecase.listoauthgrantsforaccount.OAuthGrantsRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.revokeaccountsession.RevokeAccountSessionService;
import com.clavaris.identity.application.usecase.revokeaccountsession.RevokeAccountSessionUseCase;
import com.clavaris.identity.application.usecase.revokealloauthgrantsforaccount.RevokeAllOAuthGrantsForAccountService;
import com.clavaris.identity.application.usecase.revokealloauthgrantsforaccount.RevokeAllOAuthGrantsForAccountUseCase;
import com.clavaris.identity.application.usecase.revokeoauthgrant.RevokeOAuthGrantService;
import com.clavaris.identity.application.usecase.revokeoauthgrant.RevokeOAuthGrantUseCase;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountTokenRevoker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the identity use cases for: active sessions and OAuth grants.
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
class SessionUseCaseConfig {

  // Written out explicitly for the same reason as Argon2PasswordHasher's own constructor
  // (see its comment) — only Spring's own component scan ever needs to instantiate this class.
  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ SessionUseCaseConfig() {
    // Intentionally empty — this class holds no state, only @Bean methods.
  }

  // Clerk "OAuth" tab parity (ADR-0026).
  @Bean
  /* package */ ListOAuthGrantsForAccountUseCase listOAuthGrantsForAccountUseCase(
      final OAuthGrantsRepository oauthGrants) {
    return new ListOAuthGrantsForAccountService(oauthGrants);
  }

  @Bean
  /* package */ RevokeOAuthGrantUseCase revokeOAuthGrantUseCase(
      final OAuthGrantsRepository oauthGrants, final AuditEventRecorder auditEvents) {
    return new RevokeOAuthGrantService(oauthGrants, auditEvents);
  }

  @Bean
  /* package */ RevokeAllOAuthGrantsForAccountUseCase revokeAllOAuthGrantsForAccountUseCase(
      final AccountTokenRevoker accountTokenRevoker, final AuditEventRecorder auditEvents) {
    return new RevokeAllOAuthGrantsForAccountService(accountTokenRevoker, auditEvents);
  }

  // Self-service sessions/devices page — see AccountActiveSessionsRepository's own Javadoc for
  // why this one port backs both this bean and revokeAccountSessionUseCase below.
  @Bean
  /* package */ ListActiveSessionsForAccountUseCase listActiveSessionsForAccountUseCase(
      final AccountActiveSessionsRepository activeSessions) {
    return new ListActiveSessionsForAccountService(activeSessions);
  }

  @Bean
  /* package */ RevokeAccountSessionUseCase revokeAccountSessionUseCase(
      final AccountActiveSessionsRepository activeSessions,
      final AccountRepository accounts,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox) {
    return new RevokeAccountSessionService(activeSessions, accounts, auditEvents, outbox);
  }
}
