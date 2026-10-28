package com.clavaris.identity.infrastructure.config;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.OrganizationSocialLoginPolicyProvider;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.SocialIdentityRepository;
import com.clavaris.identity.application.usecase.connectsocialidentity.ConnectSocialIdentityService;
import com.clavaris.identity.application.usecase.connectsocialidentity.ConnectSocialIdentityUseCase;
import com.clavaris.identity.application.usecase.disconnectsocialidentity.DisconnectSocialIdentityService;
import com.clavaris.identity.application.usecase.disconnectsocialidentity.DisconnectSocialIdentityUseCase;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.registerwebauthncredential.WebAuthnCredentialRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * TD-FUT-043: the signed-in End-User's "Connected accounts" actions, kept apart from {@link
 * SelfServiceCredentialUseCaseConfig} (password and email) so neither class couples to the other's
 * ports.
 */
@Configuration
class ConnectedAccountsUseCaseConfig {

  // Same explicit constructor as the other use-case configurations, same reason.
  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ ConnectedAccountsUseCaseConfig() {
    // Intentionally empty — this class holds no state, only @Bean methods.
  }

  @Bean
  /* package */ DisconnectSocialIdentityUseCase disconnectSocialIdentityUseCase(
      final AccountRepository accounts,
      final SocialIdentityRepository identities,
      final WebAuthnCredentialRepository passkeys,
      final EventOutboxWriter outbox,
      final AuditEventRecorder auditEvents) {
    return new DisconnectSocialIdentityService(accounts, identities, passkeys, outbox, auditEvents);
  }

  @Bean
  /* package */ ConnectSocialIdentityUseCase connectSocialIdentityUseCase(
      final AccountRepository accounts,
      final SocialIdentityRepository identities,
      final OrganizationSocialLoginPolicyProvider policy,
      final EventOutboxWriter outbox,
      final AuditEventRecorder auditEvents) {
    return new ConnectSocialIdentityService(accounts, identities, policy, outbox, auditEvents);
  }
}
