package com.clavaris.clientregistry.infrastructure.config;

import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.BootstrapPlatformClientService;
import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.BootstrapPlatformClientUseCase;
import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.ClientSecretHasher;
import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.PlatformClientRepository;
import com.clavaris.clientregistry.application.usecase.createorganizationclient.CreateOrganizationClientService;
import com.clavaris.clientregistry.application.usecase.createorganizationclient.CreateOrganizationClientUseCase;
import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientRepository;
import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientSecretGenerator;
import com.clavaris.clientregistry.application.usecase.deactivateorganizationclient.DeactivateOrganizationClientService;
import com.clavaris.clientregistry.application.usecase.deactivateorganizationclient.DeactivateOrganizationClientUseCase;
import com.clavaris.clientregistry.application.usecase.deactivateplatformclient.DeactivatePlatformClientService;
import com.clavaris.clientregistry.application.usecase.deactivateplatformclient.DeactivatePlatformClientUseCase;
import com.clavaris.clientregistry.application.usecase.listorganizationclients.ListOrganizationClientsService;
import com.clavaris.clientregistry.application.usecase.listorganizationclients.ListOrganizationClientsUseCase;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.OrganizationEnvironmentChecker;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.OrganizationExistsChecker;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientService;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientUseCase;
import com.clavaris.clientregistry.application.usecase.rotateorganizationclientsecret.RotateOrganizationClientSecretService;
import com.clavaris.clientregistry.application.usecase.rotateorganizationclientsecret.RotateOrganizationClientSecretUseCase;
import com.clavaris.clientregistry.application.usecase.rotateplatformclientsecret.PlatformClientSecretGenerator;
import com.clavaris.clientregistry.application.usecase.rotateplatformclientsecret.RotatePlatformClientSecretService;
import com.clavaris.clientregistry.application.usecase.rotateplatformclientsecret.RotatePlatformClientSecretUseCase;
import com.clavaris.common.application.port.AuditEventRecorder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires application-layer use cases to Spring's context — same rationale as identity-module's own
 * {@code IdentityUseCaseConfig}. Named with a module prefix, not plain {@code UseCaseConfig}:
 * confirmed live that both classes registering as the default bean name {@code "useCaseConfig"}
 * (Spring's own class-name-derived default) collides the moment both modules are on the same
 * classpath together, which they only started being once this class existed.
 */
@SuppressWarnings("PMD.LongVariable")
@Configuration
class ClientRegistryUseCaseConfig {

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ ClientRegistryUseCaseConfig() {
    // Intentionally empty — this class holds no state, only the @Bean method below.
  }

  @Bean
  /* package */ BootstrapPlatformClientUseCase bootstrapPlatformClientUseCase(
      final PlatformClientRepository platformClients, final ClientSecretHasher hasher) {
    return new BootstrapPlatformClientService(platformClients, hasher);
  }

  @Bean
  /* package */ RegisterOAuthClientUseCase registerOAuthClientUseCase(
      final OAuthClientRepository oauthClients,
      final OrganizationExistsChecker orgExistsChecker,
      @SuppressWarnings("PMD.LongVariable") final OrganizationEnvironmentChecker environmentChecker,
      final ClientSecretHasher hasher) {
    return new RegisterOAuthClientService(
        oauthClients, orgExistsChecker, environmentChecker, hasher);
  }

  // TD-SEC-018
  @Bean
  /* package */ RotatePlatformClientSecretUseCase rotatePlatformClientSecretUseCase(
      final PlatformClientRepository platformClients,
      final ClientSecretHasher hasher,
      final PlatformClientSecretGenerator secretGenerator,
      final AuditEventRecorder auditEvents) {
    return new RotatePlatformClientSecretService(
        platformClients, hasher, secretGenerator, auditEvents);
  }

  // TD-SEC-018
  @Bean
  /* package */ DeactivatePlatformClientUseCase deactivatePlatformClientUseCase(
      final PlatformClientRepository platformClients, final AuditEventRecorder auditEvents) {
    return new DeactivatePlatformClientService(platformClients, auditEvents);
  }

  // ADR-0023
  @Bean
  /* package */ CreateOrganizationClientUseCase createOrganizationClientUseCase(
      final OrganizationClientRepository organizationClients,
      final OrganizationExistsChecker orgExistsChecker,
      final OrganizationEnvironmentChecker environmentChecker,
      final ClientSecretHasher hasher,
      final OrganizationClientSecretGenerator secretGenerator,
      final AuditEventRecorder auditEvents) {
    return new CreateOrganizationClientService(
        organizationClients,
        orgExistsChecker,
        environmentChecker,
        hasher,
        secretGenerator,
        auditEvents);
  }

  // ADR-0023
  @Bean
  /* package */ RotateOrganizationClientSecretUseCase rotateOrganizationClientSecretUseCase(
      final OrganizationClientRepository organizationClients,
      final ClientSecretHasher hasher,
      final OrganizationClientSecretGenerator secretGenerator,
      final AuditEventRecorder auditEvents) {
    return new RotateOrganizationClientSecretService(
        organizationClients, hasher, secretGenerator, auditEvents);
  }

  // ADR-0023
  @Bean
  /* package */ DeactivateOrganizationClientUseCase deactivateOrganizationClientUseCase(
      final OrganizationClientRepository organizationClients,
      final AuditEventRecorder auditEvents) {
    return new DeactivateOrganizationClientService(organizationClients, auditEvents);
  }

  // ADR-0023
  @Bean
  /* package */ ListOrganizationClientsUseCase listOrganizationClientsUseCase(
      final OrganizationClientRepository organizationClients) {
    return new ListOrganizationClientsService(organizationClients);
  }
}
