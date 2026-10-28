package com.clavaris.organization.infrastructure.config;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.addaccessrestrictionentry.AccessRestrictionEntryRepository;
import com.clavaris.organization.application.usecase.addaccessrestrictionentry.AddAccessRestrictionEntryService;
import com.clavaris.organization.application.usecase.addaccessrestrictionentry.AddAccessRestrictionEntryUseCase;
import com.clavaris.organization.application.usecase.checkaccessrestrictionfororganization.CheckAccessRestrictionForOrganizationService;
import com.clavaris.organization.application.usecase.checkaccessrestrictionfororganization.CheckAccessRestrictionForOrganizationUseCase;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.deleteorganizationsocialcredential.DeleteOrganizationSocialCredentialService;
import com.clavaris.organization.application.usecase.deleteorganizationsocialcredential.DeleteOrganizationSocialCredentialUseCase;
import com.clavaris.organization.application.usecase.getaccountauthenticationpolicyfororganization.GetAccountAuthenticationPolicyForOrganizationService;
import com.clavaris.organization.application.usecase.getaccountauthenticationpolicyfororganization.GetAccountAuthenticationPolicyForOrganizationUseCase;
import com.clavaris.organization.application.usecase.getorganizationapikeys.GetOrganizationApiKeysService;
import com.clavaris.organization.application.usecase.getorganizationapikeys.GetOrganizationApiKeysUseCase;
import com.clavaris.organization.application.usecase.getorganizationapikeys.OrganizationSigningKeyPublicKeyProvider;
import com.clavaris.organization.application.usecase.getsocialconnections.GetSocialConnectionsService;
import com.clavaris.organization.application.usecase.getsocialconnections.GetSocialConnectionsUseCase;
import com.clavaris.organization.application.usecase.listaccessrestrictionentriesfororganization.ListAccessRestrictionEntriesForOrganizationService;
import com.clavaris.organization.application.usecase.listaccessrestrictionentriesfororganization.ListAccessRestrictionEntriesForOrganizationUseCase;
import com.clavaris.organization.application.usecase.listorganizationsocialcredentials.ListOrganizationSocialCredentialsService;
import com.clavaris.organization.application.usecase.listorganizationsocialcredentials.ListOrganizationSocialCredentialsUseCase;
import com.clavaris.organization.application.usecase.removeaccessrestrictionentry.RemoveAccessRestrictionEntryService;
import com.clavaris.organization.application.usecase.removeaccessrestrictionentry.RemoveAccessRestrictionEntryUseCase;
import com.clavaris.organization.application.usecase.savesocialconnection.SaveSocialConnectionService;
import com.clavaris.organization.application.usecase.savesocialconnection.SaveSocialConnectionUseCase;
import com.clavaris.organization.application.usecase.savesocialconnection.SocialConnectionSettingsRepository;
import com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization.AccountAuthenticationPolicyRepository;
import com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization.SetAccountAuthenticationPolicyForOrganizationService;
import com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization.SetAccountAuthenticationPolicyForOrganizationUseCase;
import com.clavaris.organization.application.usecase.setorganizationsocialcredential.OrganizationSocialCredentialCipher;
import com.clavaris.organization.application.usecase.setorganizationsocialcredential.OrganizationSocialCredentialRepository;
import com.clavaris.organization.application.usecase.setorganizationsocialcredential.SetOrganizationSocialCredentialService;
import com.clavaris.organization.application.usecase.setorganizationsocialcredential.SetOrganizationSocialCredentialUseCase;
import com.clavaris.organization.application.usecase.setsocialloginpolicyfororganization.SetSocialLoginPolicyForOrganizationService;
import com.clavaris.organization.application.usecase.setsocialloginpolicyfororganization.SetSocialLoginPolicyForOrganizationUseCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Organization security settings (social login policy and credentials, API keys, access
 * restrictions) to Spring's context. Split from {@code OrganizationUseCaseConfig} (TD-ARCH-042).
 * Named with a module prefix, not plain {@code UseCaseConfig}: confirmed live
 * (client-registry-module's own {@code ClientRegistryUseCaseConfig}) that the default
 * class-name-derived bean name collides the moment two modules sharing that plain name are on the
 * same classpath.
 */
// Literals: the repeated string is "PMD.LongVariable" itself, reused across several @Bean
// method parameters that legitimately share the same port name — same false positive
// identity-module's own IdentityUseCaseConfig class-level suppression already documents.
// ExcessiveImports/CouplingBetweenObjects/TooManyMethods: this class's whole job is wiring one
// @Bean method per use case (this file's own doc comment) — the Workspace feature, then ADR-0022's
// social-credential use cases, each added more, tipping method/import/collaborator counts over
// PMD's default thresholds. Same "wiring, not sprawl" reasoning
// OrganizationAuthorizationServerConfig's
// own class-level Javadoc already documents for an identical situation, not worth splitting a class
// whose entire job is Spring bean assembly.
@SuppressWarnings({"PMD.ExcessiveImports", "PMD.CouplingBetweenObjects"})
@Configuration
class OrganizationSecurityUseCaseConfig {

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ OrganizationSecurityUseCaseConfig() {
    // Intentionally empty — this class holds no state, only the @Bean method below.
  }

  // PMD.LinguisticNaming: same false positive SetRateLimitPolicyForOrganizationUseCase's own
  // @Bean method already documents above — this bean's name mirrors the use case's own name.
  @SuppressWarnings({"PMD.LinguisticNaming"})
  @Bean
  /* package */ SetSocialLoginPolicyForOrganizationUseCase
      setSocialLoginPolicyForOrganizationUseCase(
          final OrganizationRepository organizations,
          final OrganizationSocialCredentialRepository credentials,
          final AuditEventRecorder auditEvents) {
    return new SetSocialLoginPolicyForOrganizationService(organizations, credentials, auditEvents);
  }

  // PMD.LinguisticNaming: same false positive SetRateLimitPolicyForOrganizationUseCase's own
  // @Bean method already documents above.
  @SuppressWarnings("PMD.LinguisticNaming")
  @Bean
  /* package */ SetOrganizationSocialCredentialUseCase setOrganizationSocialCredentialUseCase(
      final OrganizationRepository organizations,
      final OrganizationSocialCredentialRepository credentials,
      final OrganizationSocialCredentialCipher cipher,
      final AuditEventRecorder auditEvents) {
    return new SetOrganizationSocialCredentialService(
        organizations, credentials, cipher, auditEvents);
  }

  @Bean
  /* package */ ListOrganizationSocialCredentialsUseCase listOrganizationSocialCredentialsUseCase(
      final OrganizationSocialCredentialRepository credentials) {
    return new ListOrganizationSocialCredentialsService(credentials);
  }

  @Bean
  /* package */ DeleteOrganizationSocialCredentialUseCase deleteOrganizationSocialCredentialUseCase(
      final OrganizationRepository organizations,
      final OrganizationSocialCredentialRepository credentials,
      final AuditEventRecorder auditEvents) {
    return new DeleteOrganizationSocialCredentialService(organizations, credentials, auditEvents);
  }

  // The dashboard's "Connections" page: one transactional save of a provider's switch, credentials
  // and settings, and the read that feeds the page.
  @Bean
  /* package */ SaveSocialConnectionUseCase saveSocialConnectionUseCase(
      final OrganizationRepository organizations,
      final OrganizationSocialCredentialRepository credentials,
      final OrganizationSocialCredentialCipher cipher,
      final SocialConnectionSettingsRepository settings,
      final AuditEventRecorder auditEvents) {
    return new SaveSocialConnectionService(
        organizations, credentials, cipher, settings, auditEvents);
  }

  @Bean
  /* package */ GetSocialConnectionsUseCase getSocialConnectionsUseCase(
      final OrganizationRepository organizations,
      final OrganizationSocialCredentialRepository credentials,
      final SocialConnectionSettingsRepository settings,
      @Value("${CLAVARIS_BASE_URL:http://localhost:8080}") final String clavarisBaseUrl) {
    return new GetSocialConnectionsService(organizations, credentials, settings, clavarisBaseUrl);
  }

  // ADR-0023 / Clerk API-keys parity
  @Bean
  /* package */ GetOrganizationApiKeysUseCase getOrganizationApiKeysUseCase(
      final OrganizationRepository organizations,
      final OrganizationSigningKeyPublicKeyProvider publicKeyProvider,
      @Value("${CLAVARIS_BASE_URL:http://localhost:8080}") final String clavarisBaseUrl) {
    return new GetOrganizationApiKeysService(organizations, publicKeyProvider, clavarisBaseUrl);
  }

  // ADR-0024 / Clerk sign-up-sign-in-options parity. PMD.LinguisticNaming: same false positive
  // SetRateLimitPolicyForOrganizationUseCase's own @Bean method already documents above.
  @SuppressWarnings("PMD.LinguisticNaming")
  @Bean
  /* package */ SetAccountAuthenticationPolicyForOrganizationUseCase
      setAccountAuthenticationPolicyForOrganizationUseCase(
          final OrganizationRepository organizations,
          final AccountAuthenticationPolicyRepository policies,
          final AuditEventRecorder auditEvents) {
    return new SetAccountAuthenticationPolicyForOrganizationService(
        organizations, policies, auditEvents);
  }

  @Bean
  /* package */ GetAccountAuthenticationPolicyForOrganizationUseCase
      getAccountAuthenticationPolicyForOrganizationUseCase(
          final AccountAuthenticationPolicyRepository policies) {
    return new GetAccountAuthenticationPolicyForOrganizationService(policies);
  }

  // SDE-III review, 2026-09-19 — Clerk "Restrictions" parity, minimal.
  @Bean
  /* package */ AddAccessRestrictionEntryUseCase addAccessRestrictionEntryUseCase(
      final AccessRestrictionEntryRepository entries) {
    return new AddAccessRestrictionEntryService(entries);
  }

  @Bean
  /* package */ RemoveAccessRestrictionEntryUseCase removeAccessRestrictionEntryUseCase(
      final AccessRestrictionEntryRepository entries) {
    return new RemoveAccessRestrictionEntryService(entries);
  }

  @Bean
  /* package */ ListAccessRestrictionEntriesForOrganizationUseCase
      listAccessRestrictionEntriesForOrganizationUseCase(
          final AccessRestrictionEntryRepository entries) {
    return new ListAccessRestrictionEntriesForOrganizationService(entries);
  }

  @Bean
  /* package */ CheckAccessRestrictionForOrganizationUseCase
      checkAccessRestrictionForOrganizationUseCase(final AccessRestrictionEntryRepository entries) {
    return new CheckAccessRestrictionForOrganizationService(entries);
  }
}
