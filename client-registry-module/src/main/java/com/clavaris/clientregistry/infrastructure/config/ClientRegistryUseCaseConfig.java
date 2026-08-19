package com.clavaris.clientregistry.infrastructure.config;

import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.BootstrapPlatformClientService;
import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.BootstrapPlatformClientUseCase;
import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.ClientSecretHasher;
import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.PlatformClientRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires application-layer use cases to Spring's context — same rationale as identity-module's own
 * {@code IdentityUseCaseConfig}. Named with a module prefix, not plain {@code UseCaseConfig}:
 * confirmed live that both classes registering as the default bean name {@code "useCaseConfig"}
 * (Spring's own class-name-derived default) collides the moment both modules are on the same
 * classpath together, which they only started being once this class existed.
 */
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
}
