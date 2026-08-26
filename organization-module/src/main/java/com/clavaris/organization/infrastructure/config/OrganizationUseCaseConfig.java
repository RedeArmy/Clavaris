package com.clavaris.organization.infrastructure.config;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.createorganization.CreateOrganizationService;
import com.clavaris.organization.application.usecase.createorganization.CreateOrganizationUseCase;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.createorganization.PlatformAccountExistsChecker;
import com.clavaris.organization.application.usecase.createorganization.SigningKeyProvisioner;
import com.clavaris.organization.application.usecase.deleteorganization.DeleteOrganizationService;
import com.clavaris.organization.application.usecase.deleteorganization.DeleteOrganizationUseCase;
import com.clavaris.organization.application.usecase.deleteorganization.OrganizationIdentityDataEraser;
import com.clavaris.organization.application.usecase.deleteorganization.OrganizationOAuthClientsEraser;
import com.clavaris.organization.application.usecase.deleteorganization.OrganizationTokenRevoker;
import com.clavaris.organization.application.usecase.listorganizationsforplatformaccount.ListOrganizationsForPlatformAccountService;
import com.clavaris.organization.application.usecase.listorganizationsforplatformaccount.ListOrganizationsForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.RateLimitPolicyRepository;
import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.SetRateLimitPolicyForOrganizationService;
import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.SetRateLimitPolicyForOrganizationUseCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires application-layer use cases to Spring's context — same rationale as identity-module's
 * {@code IdentityUseCaseConfig}. Named with a module prefix, not plain {@code UseCaseConfig}:
 * confirmed live (client-registry-module's own {@code ClientRegistryUseCaseConfig}) that the
 * default class-name-derived bean name collides the moment two modules sharing that plain name are
 * on the same classpath.
 */
// Literals: the repeated string is "PMD.LongVariable" itself, reused across several @Bean
// method parameters that legitimately share the same port name — same false positive
// identity-module's own IdentityUseCaseConfig class-level suppression already documents.
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
@Configuration
class OrganizationUseCaseConfig {

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ OrganizationUseCaseConfig() {
    // Intentionally empty — this class holds no state, only the @Bean method below.
  }

  @SuppressWarnings("PMD.LongVariable")
  @Bean
  /* package */ CreateOrganizationUseCase createOrganizationUseCase(
      final OrganizationRepository organizations,
      final SigningKeyProvisioner keyProvisioner,
      final PlatformAccountExistsChecker platformAccountExistsChecker,
      final AuditEventRecorder auditEvents) {
    return new CreateOrganizationService(
        organizations, keyProvisioner, platformAccountExistsChecker, auditEvents);
  }

  @Bean
  /* package */ ListOrganizationsForPlatformAccountUseCase
      listOrganizationsForPlatformAccountUseCase(final OrganizationRepository organizations) {
    return new ListOrganizationsForPlatformAccountService(organizations);
  }

  // ADR-0010 §6.2: the hard system-wide cap no Organization's own RateLimitPolicy may ever
  // exceed — an operational value, not a domain constant, so it's configured here rather than
  // hardcoded in RateLimitPolicy itself. Default (6000/min = 100 req/s) is 10x
  // OrganizationCapacityRateLimitingFilter's own system default ceiling (600/min) — real headroom
  // to tune up a legitimately large tenant without the domain-level check ever being the actual
  // bottleneck.
  //
  // PMD.LinguisticNaming: this bean's name matches SetRateLimitPolicyForOrganizationUseCase
  // itself (lowercased first letter), the same convention every other @Bean method in this class
  // follows — it starts with "set" because the *use case* is named "Set...", not because this is
  // a JavaBean setter PMD's own naming check assumes it is.
  @SuppressWarnings({"PMD.LinguisticNaming"})
  @Bean
  /* package */ SetRateLimitPolicyForOrganizationUseCase setRateLimitPolicyForOrganizationUseCase(
      final OrganizationRepository organizations,
      final RateLimitPolicyRepository policies,
      @Value("${clavaris.rate-limit.capacity.hard-cap-requests-per-minute:6000}")
          final int hardSystemWideCap,
      final AuditEventRecorder auditEvents) {
    return new SetRateLimitPolicyForOrganizationService(
        organizations, policies, hardSystemWideCap, auditEvents);
  }

  @Bean
  /* package */ DeleteOrganizationUseCase deleteOrganizationUseCase(
      final OrganizationRepository organizations,
      @SuppressWarnings("PMD.LongVariable") final OrganizationTokenRevoker organizationTokenRevoker,
      @SuppressWarnings("PMD.LongVariable") final OrganizationIdentityDataEraser identityDataEraser,
      @SuppressWarnings("PMD.LongVariable") final OrganizationOAuthClientsEraser oauthClientsEraser,
      final AuditEventRecorder auditEvents) {
    return new DeleteOrganizationService(
        organizations,
        organizationTokenRevoker,
        identityDataEraser,
        oauthClientsEraser,
        auditEvents);
  }
}
