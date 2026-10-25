package com.clavaris.identity.infrastructure.config;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.getaccountavatar.GetAccountAvatarService;
import com.clavaris.identity.application.usecase.getaccountavatar.GetAccountAvatarUseCase;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.removeaccountprofilepicture.RemoveAccountProfilePictureService;
import com.clavaris.identity.application.usecase.removeaccountprofilepicture.RemoveAccountProfilePictureUseCase;
import com.clavaris.identity.application.usecase.updateaccountmetadata.UpdateAccountMetadataService;
import com.clavaris.identity.application.usecase.updateaccountmetadata.UpdateAccountMetadataUseCase;
import com.clavaris.identity.application.usecase.updateaccountpermissions.UpdateAccountPermissionsService;
import com.clavaris.identity.application.usecase.updateaccountpermissions.UpdateAccountPermissionsUseCase;
import com.clavaris.identity.application.usecase.updateaccountprofile.UpdateAccountProfileService;
import com.clavaris.identity.application.usecase.updateaccountprofile.UpdateAccountProfileUseCase;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.ProfilePictureStorage;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.UpdateAccountProfilePictureService;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.UpdateAccountProfilePictureUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires the identity use cases for: account profile, permissions, metadata and avatar.
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
class AccountProfileUseCaseConfig {

  // Written out explicitly for the same reason as Argon2PasswordHasher's own constructor
  // (see its comment) — only Spring's own component scan ever needs to instantiate this class.
  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ AccountProfileUseCaseConfig() {
    // Intentionally empty — this class holds no state, only @Bean methods.
  }

  @Bean
  /* package */ UpdateAccountProfileUseCase updateAccountProfileUseCase(
      final AccountRepository accounts,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox) {
    return new UpdateAccountProfileService(accounts, auditEvents, outbox);
  }

  @Bean
  /* package */ UpdateAccountPermissionsUseCase updateAccountPermissionsUseCase(
      final AccountRepository accounts, final AuditEventRecorder auditEvents) {
    return new UpdateAccountPermissionsService(accounts, auditEvents);
  }

  // TD-FUT-034, Clerk "Metadata" parity.
  @Bean
  /* package */ UpdateAccountMetadataUseCase updateAccountMetadataUseCase(
      final AccountRepository accounts,
      final AuditEventRecorder auditEvents,
      final ObjectMapper objectMapper) {
    return new UpdateAccountMetadataService(accounts, auditEvents, objectMapper);
  }

  // ADR-0026: needs its own TransactionTemplate (not @Transactional on handle()) — same "the
  // network call to the storage backend must never run inside an open database transaction"
  // reasoning AuthenticateWithSocialProviderUseCase's own @Bean method above already documents.
  @Bean
  /* package */ UpdateAccountProfilePictureUseCase updateAccountProfilePictureUseCase(
      final AccountRepository accounts,
      final ProfilePictureStorage profilePictureStorage,
      final AuditEventRecorder auditEvents,
      final PlatformTransactionManager transactionManager) {
    return new UpdateAccountProfilePictureService(
        accounts, profilePictureStorage, auditEvents, new TransactionTemplate(transactionManager));
  }

  @Bean
  /* package */ RemoveAccountProfilePictureUseCase removeAccountProfilePictureUseCase(
      final AccountRepository accounts,
      final ProfilePictureStorage profilePictureStorage,
      final AuditEventRecorder auditEvents,
      final PlatformTransactionManager transactionManager) {
    return new RemoveAccountProfilePictureService(
        accounts, profilePictureStorage, auditEvents, new TransactionTemplate(transactionManager));
  }

  @Bean
  /* package */ GetAccountAvatarUseCase getAccountAvatarUseCase(
      final AccountRepository accounts, final ProfilePictureStorage profilePictureStorage) {
    return new GetAccountAvatarService(accounts, profilePictureStorage);
  }
}
