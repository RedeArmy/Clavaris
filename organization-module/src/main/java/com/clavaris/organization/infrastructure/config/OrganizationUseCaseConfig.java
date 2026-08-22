package com.clavaris.organization.infrastructure.config;

import com.clavaris.organization.application.usecase.createorganization.CreateOrganizationService;
import com.clavaris.organization.application.usecase.createorganization.CreateOrganizationUseCase;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.createorganization.PlatformAccountExistsChecker;
import com.clavaris.organization.application.usecase.createorganization.SigningKeyProvisioner;
import com.clavaris.organization.application.usecase.listorganizationsforplatformaccount.ListOrganizationsForPlatformAccountService;
import com.clavaris.organization.application.usecase.listorganizationsforplatformaccount.ListOrganizationsForPlatformAccountUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires application-layer use cases to Spring's context — same rationale as identity-module's
 * {@code IdentityUseCaseConfig}. Named with a module prefix, not plain {@code UseCaseConfig}:
 * confirmed live (client-registry-module's own {@code ClientRegistryUseCaseConfig}) that the
 * default class-name-derived bean name collides the moment two modules sharing that plain name are
 * on the same classpath.
 */
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
      final PlatformAccountExistsChecker platformAccountExistsChecker) {
    return new CreateOrganizationService(
        organizations, keyProvisioner, platformAccountExistsChecker);
  }

  @Bean
  /* package */ ListOrganizationsForPlatformAccountUseCase
      listOrganizationsForPlatformAccountUseCase(final OrganizationRepository organizations) {
    return new ListOrganizationsForPlatformAccountService(organizations);
  }
}
