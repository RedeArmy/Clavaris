package com.clavaris.identity.infrastructure.config;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.registeraccount.PasswordHasher;
import com.clavaris.identity.application.usecase.registeraccount.RegisterAccountService;
import com.clavaris.identity.application.usecase.registeraccount.RegisterAccountUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires application-layer use cases to Spring's context. Deliberately kept out of {@code
 * application/usecase/registeraccount} itself: {@code RegisterAccountService}'s only Spring
 * dependency is the {@code @Transactional} annotation on its {@code handle} method
 * (first-vertical-slice-blueprint.md §2.3) — bean registration for it lives here, in
 * infrastructure, not as a class-level {@code @Service} on the service itself.
 */
@Configuration
class UseCaseConfig {

  // Written out explicitly for the same reason as Argon2PasswordHasher's own constructor
  // (see its comment) — only Spring's own component scan ever needs to instantiate this class.
  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ UseCaseConfig() {
    // Intentionally empty — this class holds no state, only the @Bean method below.
  }

  // This bean-definition method has no reason to be called directly by anything outside Spring's
  // own container wiring — RegisterAccountUseCase (the interface) is what every other caller,
  // including other modules, should depend on.
  @Bean
  /* package */ RegisterAccountUseCase registerAccountUseCase(
      final AccountRepository accountRepository,
      final PasswordHasher passwordHasher,
      final EventOutboxWriter eventOutboxWriter) {
    return new RegisterAccountService(accountRepository, passwordHasher, eventOutboxWriter);
  }
}
