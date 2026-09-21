package com.clavaris.identity.infrastructure.config;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.identity.application.usecase.authenticateplatformaccountwithpassword.AuthenticatePlatformAccountWithPasswordService;
import com.clavaris.identity.application.usecase.authenticateplatformaccountwithpassword.AuthenticatePlatformAccountWithPasswordUseCase;
import com.clavaris.identity.application.usecase.authenticateplatformaccountwithsocialprovider.AuthenticatePlatformAccountWithSocialProviderService;
import com.clavaris.identity.application.usecase.authenticateplatformaccountwithsocialprovider.AuthenticatePlatformAccountWithSocialProviderUseCase;
import com.clavaris.identity.application.usecase.authenticateplatformaccountwithsocialprovider.PendingPlatformSocialLinkRepository;
import com.clavaris.identity.application.usecase.authenticateplatformaccountwithsocialprovider.PlatformSocialIdentityRepository;
import com.clavaris.identity.application.usecase.authenticatewithpassword.PasswordVerifier;
import com.clavaris.identity.application.usecase.confirmnewplatformdeviceloginalert.ConfirmNewPlatformDeviceLoginAlertService;
import com.clavaris.identity.application.usecase.confirmnewplatformdeviceloginalert.ConfirmNewPlatformDeviceLoginAlertUseCase;
import com.clavaris.identity.application.usecase.confirmpendingplatformsociallink.ConfirmPendingPlatformSocialLinkService;
import com.clavaris.identity.application.usecase.confirmpendingplatformsociallink.ConfirmPendingPlatformSocialLinkUseCase;
import com.clavaris.identity.application.usecase.confirmplatformaccountemailverification.ConfirmPlatformAccountEmailVerificationService;
import com.clavaris.identity.application.usecase.confirmplatformaccountemailverification.ConfirmPlatformAccountEmailVerificationUseCase;
import com.clavaris.identity.application.usecase.confirmplatformaccountpasswordreset.ConfirmPlatformAccountPasswordResetService;
import com.clavaris.identity.application.usecase.confirmplatformaccountpasswordreset.ConfirmPlatformAccountPasswordResetUseCase;
import com.clavaris.identity.application.usecase.confirmplatformaccountpasswordreset.PlatformAccountSessionRevoker;
import com.clavaris.identity.application.usecase.getplatformaccountavatar.GetPlatformAccountAvatarService;
import com.clavaris.identity.application.usecase.getplatformaccountavatar.GetPlatformAccountAvatarUseCase;
import com.clavaris.identity.application.usecase.listactivesessionsforplatformaccount.ListActiveSessionsForPlatformAccountService;
import com.clavaris.identity.application.usecase.listactivesessionsforplatformaccount.ListActiveSessionsForPlatformAccountUseCase;
import com.clavaris.identity.application.usecase.listactivesessionsforplatformaccount.PlatformAccountActiveSessionsRepository;
import com.clavaris.identity.application.usecase.listconnectedaccountsforplatformaccount.ListConnectedAccountsForPlatformAccountService;
import com.clavaris.identity.application.usecase.listconnectedaccountsforplatformaccount.ListConnectedAccountsForPlatformAccountUseCase;
import com.clavaris.identity.application.usecase.recordplatformaccountlogindevice.PlatformKnownDeviceRepository;
import com.clavaris.identity.application.usecase.recordplatformaccountlogindevice.RecordPlatformAccountLoginDeviceService;
import com.clavaris.identity.application.usecase.recordplatformaccountlogindevice.RecordPlatformAccountLoginDeviceUseCase;
import com.clavaris.identity.application.usecase.registeraccount.PasswordHasher;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.registerplatformaccount.RegisterPlatformAccountService;
import com.clavaris.identity.application.usecase.registerplatformaccount.RegisterPlatformAccountUseCase;
import com.clavaris.identity.application.usecase.removeplatformaccountprofilepicture.RemovePlatformAccountProfilePictureService;
import com.clavaris.identity.application.usecase.removeplatformaccountprofilepicture.RemovePlatformAccountProfilePictureUseCase;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.PlatformMailSender;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.PlatformVerificationTokenRepository;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.RequestPlatformAccountEmailVerificationService;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.RequestPlatformAccountEmailVerificationUseCase;
import com.clavaris.identity.application.usecase.requestplatformaccountpasswordreset.RequestPlatformAccountPasswordResetService;
import com.clavaris.identity.application.usecase.requestplatformaccountpasswordreset.RequestPlatformAccountPasswordResetUseCase;
import com.clavaris.identity.application.usecase.revokeplatformaccountsession.RevokePlatformAccountSessionService;
import com.clavaris.identity.application.usecase.revokeplatformaccountsession.RevokePlatformAccountSessionUseCase;
import com.clavaris.identity.application.usecase.suspendplatformaccount.SuspendPlatformAccountService;
import com.clavaris.identity.application.usecase.suspendplatformaccount.SuspendPlatformAccountUseCase;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.ProfilePictureStorage;
import com.clavaris.identity.application.usecase.updateplatformaccountprofilepicture.UpdatePlatformAccountProfilePictureService;
import com.clavaris.identity.application.usecase.updateplatformaccountprofilepicture.UpdatePlatformAccountProfilePictureUseCase;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-0012: wires {@code PlatformAccount}'s own use cases to Spring's context — split out from
 * {@link IdentityUseCaseConfig} (which carries its own {@code PMD.ExcessiveImports}/{@code
 * CouplingBetweenObjects} suppressions for the tenant-tier's own use cases) purely to keep each
 * file a manageable size; both classes wire the same module's use cases, split by bounded concern
 * (tenant {@code Account} vs. platform {@code PlatformAccount}), not by anything architectural.
 * TD-FUT-026 (2026-09-02) added this class's own {@code ExcessiveImports}/{@code
 * CouplingBetweenObjects}/{@code TooManyMethods} suppressions, same rationale as that class's own
 * identical ones — one {@code @Bean} method per use case is what this class's entire job looks
 * like. AvoidDuplicateLiterals below: same "PMD.LongVariable" reused across four parameters
 * rationale as IdentityUseCaseConfig's own identical suppression.
 */
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.ExcessiveImports",
  "PMD.CouplingBetweenObjects",
  "PMD.TooManyMethods"
})
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

  // ADR-0020 Decision 1/2: same TransactionTemplate rationale as the tenant-tier sibling's own
  // @Bean method (organization-module's AddWorkspaceMemberUseCase set the original precedent).
  @SuppressWarnings("java:S107")
  @Bean
  /* package */ AuthenticatePlatformAccountWithSocialProviderUseCase
      authenticatePlatformAccountWithSocialProviderUseCase(
          final PlatformAccountRepository accounts,
          final PlatformSocialIdentityRepository socialIdentities,
          final PendingPlatformSocialLinkRepository pendingLinks,
          final PlatformMailSender mailSender,
          final SecurityMetricsRecorder securityMetrics,
          @SuppressWarnings("PMD.LongVariable") final PlatformTransactionManager transactionManager,
          final PasswordHasher hasher) {
    return new AuthenticatePlatformAccountWithSocialProviderService(
        accounts,
        socialIdentities,
        pendingLinks,
        mailSender,
        securityMetrics,
        new TransactionTemplate(transactionManager),
        hasher);
  }

  @Bean
  /* package */ ConfirmPendingPlatformSocialLinkUseCase confirmPendingPlatformSocialLinkUseCase(
      final PendingPlatformSocialLinkRepository pendingLinks,
      final PlatformSocialIdentityRepository socialIdentities,
      final PlatformAccountRepository accounts) {
    return new ConfirmPendingPlatformSocialLinkService(pendingLinks, socialIdentities, accounts);
  }

  // TD-FUT-026 (closed 2026-09-02): self-service sessions/devices page for a PlatformAccount —
  // platform-tier mirror of IdentityUseCaseConfig's own listActiveSessionsForAccountUseCase/
  // revokeAccountSessionUseCase beans.
  @Bean
  /* package */ ListActiveSessionsForPlatformAccountUseCase
      listActiveSessionsForPlatformAccountUseCase(
          final PlatformAccountActiveSessionsRepository activeSessions) {
    return new ListActiveSessionsForPlatformAccountService(activeSessions);
  }

  @Bean
  /* package */ RevokePlatformAccountSessionUseCase revokePlatformAccountSessionUseCase(
      final PlatformAccountActiveSessionsRepository activeSessions,
      final AuditEventRecorder auditEvents) {
    return new RevokePlatformAccountSessionService(activeSessions, auditEvents);
  }

  // New-device login email notification — platform-tier mirror of IdentityUseCaseConfig's own
  // recordAccountLoginDeviceUseCase bean. Default cutover matches this feature's own migration,
  // V20260902130000, same "the actual moment the mechanism went live" rationale that bean's own
  // comment documents.
  @Bean
  @SuppressWarnings("PMD.LongVariable")
  /* package */ RecordPlatformAccountLoginDeviceUseCase recordPlatformAccountLoginDeviceUseCase(
      final PlatformKnownDeviceRepository knownDevices,
      final PlatformAccountRepository accounts,
      final PlatformMailSender mailSender,
      final AuditEventRecorder auditEvents,
      @Value("${clavaris.platform-known-device.migration-cutover-at:2026-09-02T13:00:00Z}")
          final Instant platformKnownDeviceMigrationCutoverAt,
      @SuppressWarnings("PMD.LongVariable")
          final PlatformVerificationTokenRepository verificationTokens) {
    return new RecordPlatformAccountLoginDeviceService(
        knownDevices,
        accounts,
        mailSender,
        auditEvents,
        platformKnownDeviceMigrationCutoverAt,
        verificationTokens);
  }

  // TD-FUT-031: platform-tier mirror of IdentityUseCaseConfig's own suspendAccountUseCase bean —
  // see SuspendPlatformAccountUseCase's own Javadoc for why it's a deliberately simpler cascade.
  @Bean
  /* package */ SuspendPlatformAccountUseCase suspendPlatformAccountUseCase(
      final PlatformAccountRepository accounts,
      final PlatformAccountSessionRevoker sessionRevoker,
      final AuditEventRecorder auditEvents) {
    return new SuspendPlatformAccountService(accounts, sessionRevoker, auditEvents);
  }

  // TD-FUT-031: the "this wasn't me" half of the new-platform-device login alert — see
  // ConfirmNewPlatformDeviceLoginAlertService's own Javadoc for why this delegates straight to
  // the suspendPlatformAccountUseCase bean above rather than duplicating its cascade.
  @Bean
  /* package */ ConfirmNewPlatformDeviceLoginAlertUseCase confirmNewPlatformDeviceLoginAlertUseCase(
      @SuppressWarnings("PMD.LongVariable")
          final PlatformVerificationTokenRepository verificationTokens,
      @SuppressWarnings("PMD.LongVariable")
          final SuspendPlatformAccountUseCase suspendPlatformAccountUseCase) {
    return new ConfirmNewPlatformDeviceLoginAlertService(
        verificationTokens, suspendPlatformAccountUseCase);
  }

  // ADR-0026: needs its own TransactionTemplate (not @Transactional on handle()) — same "the
  // network call to the storage backend must never run inside an open database transaction"
  // reasoning IdentityUseCaseConfig's own updateAccountProfilePictureUseCase bean documents for
  // its tenant-tier sibling.
  @Bean
  /* package */ UpdatePlatformAccountProfilePictureUseCase
      updatePlatformAccountProfilePictureUseCase(
          final PlatformAccountRepository accounts,
          @SuppressWarnings("PMD.LongVariable") final ProfilePictureStorage profilePictureStorage,
          final AuditEventRecorder auditEvents,
          @SuppressWarnings("PMD.LongVariable")
              final PlatformTransactionManager transactionManager) {
    return new UpdatePlatformAccountProfilePictureService(
        accounts, profilePictureStorage, auditEvents, new TransactionTemplate(transactionManager));
  }

  @Bean
  /* package */ RemovePlatformAccountProfilePictureUseCase
      removePlatformAccountProfilePictureUseCase(
          final PlatformAccountRepository accounts,
          @SuppressWarnings("PMD.LongVariable") final ProfilePictureStorage profilePictureStorage,
          final AuditEventRecorder auditEvents,
          @SuppressWarnings("PMD.LongVariable")
              final PlatformTransactionManager transactionManager) {
    return new RemovePlatformAccountProfilePictureService(
        accounts, profilePictureStorage, auditEvents, new TransactionTemplate(transactionManager));
  }

  @Bean
  /* package */ GetPlatformAccountAvatarUseCase getPlatformAccountAvatarUseCase(
      final PlatformAccountRepository accounts,
      @SuppressWarnings("PMD.LongVariable") final ProfilePictureStorage profilePictureStorage) {
    return new GetPlatformAccountAvatarService(accounts, profilePictureStorage);
  }

  @Bean
  /* package */ ListConnectedAccountsForPlatformAccountUseCase
      listConnectedAccountsForPlatformAccountUseCase(
          final PlatformSocialIdentityRepository socialIdentities) {
    return new ListConnectedAccountsForPlatformAccountService(socialIdentities);
  }
}
