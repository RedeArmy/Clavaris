package com.clavaris.identity.infrastructure.config;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.activateplatformsigningkey.ActivatePlatformSigningKeyService;
import com.clavaris.identity.application.usecase.activateplatformsigningkey.ActivatePlatformSigningKeyUseCase;
import com.clavaris.identity.application.usecase.activateplatformsigningkey.PlatformSigningKeyRepository;
import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.ActivateSigningKeyForOrganizationService;
import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.ActivateSigningKeyForOrganizationUseCase;
import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.SigningKeyRepository;
import com.clavaris.identity.application.usecase.listsigningkeysfororganization.ListSigningKeysForOrganizationService;
import com.clavaris.identity.application.usecase.listsigningkeysfororganization.ListSigningKeysForOrganizationUseCase;
import com.clavaris.identity.application.usecase.purgesigningkeyfororganization.PurgeSigningKeyForOrganizationService;
import com.clavaris.identity.application.usecase.purgesigningkeyfororganization.PurgeSigningKeyForOrganizationUseCase;
import com.clavaris.identity.application.usecase.rotatesigningkeyfororganization.RotateSigningKeyForOrganizationService;
import com.clavaris.identity.application.usecase.rotatesigningkeyfororganization.RotateSigningKeyForOrganizationUseCase;
import com.clavaris.identity.application.usecase.rotatesigningkeyfororganization.SigningKeyMaterialGenerator;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the identity use cases for: per-Organization and platform signing keys (JWKS rotation with
 * overlap, ADR-0010 §5).
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
class SigningKeyUseCaseConfig {

  // Written out explicitly for the same reason as Argon2PasswordHasher's own constructor
  // (see its comment) — only Spring's own component scan ever needs to instantiate this class.
  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ SigningKeyUseCaseConfig() {
    // Intentionally empty — this class holds no state, only @Bean methods.
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
      final ActivateSigningKeyForOrganizationUseCase activateSigningKeyForOrganizationUseCase,
      final AuditEventRecorder auditEvents) {
    return new RotateSigningKeyForOrganizationService(
        signingKeys, keyMaterial, activateSigningKeyForOrganizationUseCase, auditEvents);
  }

  // TD-SEC-029
  @Bean
  /* package */ PurgeSigningKeyForOrganizationUseCase purgeSigningKeyForOrganizationUseCase(
      final SigningKeyRepository signingKeys,
      final SigningKeyMaterialGenerator keyMaterial,
      final ActivateSigningKeyForOrganizationUseCase activateSigningKeyForOrganizationUseCase,
      final AuditEventRecorder auditEvents) {
    return new PurgeSigningKeyForOrganizationService(
        signingKeys, keyMaterial, activateSigningKeyForOrganizationUseCase, auditEvents);
  }

  // SDE-III review, 2026-09-11: the dashboard's own signing-key listing page — see
  // ListSigningKeysForOrganizationUseCase's own Javadoc for why this bean didn't exist until now.
  // Same jwks-overlap-hours property app's own OrganizationAuthorizationServerConfig already reads
  // for the real JWKS-serving path — one source of truth for "what should still be visible."
  @Bean
  /* package */ ListSigningKeysForOrganizationUseCase listSigningKeysForOrganizationUseCase(
      final SigningKeyRepository signingKeys,
      @Value("${clavaris.signing-key.jwks-overlap-hours:24}") final long jwksOverlapHours) {
    return new ListSigningKeysForOrganizationService(
        signingKeys, Duration.ofHours(jwksOverlapHours));
  }
}
