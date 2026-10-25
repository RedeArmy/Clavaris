package com.clavaris.identity.infrastructure.config;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.authenticatewithwebauthn.AuthenticateWithWebAuthnService;
import com.clavaris.identity.application.usecase.authenticatewithwebauthn.AuthenticateWithWebAuthnUseCase;
import com.clavaris.identity.application.usecase.authenticatewithwebauthn.StartWebAuthnAuthenticationService;
import com.clavaris.identity.application.usecase.authenticatewithwebauthn.StartWebAuthnAuthenticationUseCase;
import com.clavaris.identity.application.usecase.deletewebauthncredential.DeleteWebAuthnCredentialService;
import com.clavaris.identity.application.usecase.deletewebauthncredential.DeleteWebAuthnCredentialUseCase;
import com.clavaris.identity.application.usecase.listwebauthncredentialsforaccount.ListWebAuthnCredentialsForAccountService;
import com.clavaris.identity.application.usecase.listwebauthncredentialsforaccount.ListWebAuthnCredentialsForAccountUseCase;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registerwebauthncredential.CompleteWebAuthnRegistrationService;
import com.clavaris.identity.application.usecase.registerwebauthncredential.CompleteWebAuthnRegistrationUseCase;
import com.clavaris.identity.application.usecase.registerwebauthncredential.RelyingPartyFactory;
import com.clavaris.identity.application.usecase.registerwebauthncredential.StartWebAuthnRegistrationService;
import com.clavaris.identity.application.usecase.registerwebauthncredential.StartWebAuthnRegistrationUseCase;
import com.clavaris.identity.application.usecase.registerwebauthncredential.WebAuthnCredentialRepository;
import com.clavaris.identity.application.usecase.resolveorganizationname.OrganizationNameProvider;
import com.yubico.webauthn.RelyingParty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the identity use cases for: passkeys (WebAuthn): registration, authentication and
 * credential management.
 *
 * <p>Deliberately kept out of {@code application/usecase} itself: a service's only Spring
 * dependency is {@code @Transactional} on its {@code handle} method
 * (first-vertical-slice-blueprint.md §2.3), so bean registration lives here, in infrastructure.
 * Split from one 868-line {@code IdentityUseCaseConfig} by area; beans reach each other through
 * ordinary constructor injection, so the split changes no wiring.
 */
@Configuration
class WebAuthnUseCaseConfig {

  // Written out explicitly for the same reason as Argon2PasswordHasher's own constructor
  // (see its comment) — only Spring's own component scan ever needs to instantiate this class.
  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ WebAuthnUseCaseConfig() {
    // Intentionally empty — this class holds no state, only @Bean methods.
  }

  // TD-FUT-034, Clerk "View Profile" passkeys parity.
  @Bean
  /* package */ StartWebAuthnRegistrationUseCase startWebAuthnRegistrationUseCase(
      final RelyingPartyFactory relyingParties, final OrganizationNameProvider organizationNames) {
    return new StartWebAuthnRegistrationService(relyingParties, organizationNames);
  }

  // TD-FUT-034, Clerk "View Profile" passkeys parity.
  @Bean
  /* package */ CompleteWebAuthnRegistrationUseCase completeWebAuthnRegistrationUseCase(
      final RelyingParty relyingParty, final WebAuthnCredentialRepository credentials) {
    return new CompleteWebAuthnRegistrationService(relyingParty, credentials);
  }

  // TD-FUT-034, Clerk "View Profile" passkeys parity.
  @Bean
  /* package */ StartWebAuthnAuthenticationUseCase startWebAuthnAuthenticationUseCase(
      final RelyingParty relyingParty) {
    return new StartWebAuthnAuthenticationService(relyingParty);
  }

  // TD-FUT-034, Clerk "View Profile" passkeys parity.
  @Bean
  /* package */ AuthenticateWithWebAuthnUseCase authenticateWithWebAuthnUseCase(
      final RelyingParty relyingParty,
      final WebAuthnCredentialRepository credentials,
      final AccountRepository accounts) {
    return new AuthenticateWithWebAuthnService(relyingParty, credentials, accounts);
  }

  // TD-FUT-034, Clerk "View Profile" passkeys parity.
  @Bean
  /* package */ ListWebAuthnCredentialsForAccountUseCase listWebAuthnCredentialsForAccountUseCase(
      final WebAuthnCredentialRepository credentials) {
    return new ListWebAuthnCredentialsForAccountService(credentials);
  }

  // TD-FUT-034, Clerk "View Profile" passkeys parity.
  @Bean
  /* package */ DeleteWebAuthnCredentialUseCase deleteWebAuthnCredentialUseCase(
      final WebAuthnCredentialRepository credentials, final AuditEventRecorder auditEvents) {
    return new DeleteWebAuthnCredentialService(credentials, auditEvents);
  }
}
