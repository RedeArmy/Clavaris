package com.clavaris.webhook.application.usecase.registerwebhookendpoint;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Orchestration for {@link RegisterWebhookEndpointUseCase}. Generates the raw signing secret
 * server-side, never accepts one from the caller — same "a machine credential is stronger generated
 * here than accepted from an operator's own choice" reasoning client-registry-module's own {@code
 * RegisterOAuthClientService} already establishes for {@code client_secret}.
 */
public class RegisterWebhookEndpointService implements RegisterWebhookEndpointUseCase {

  // 256 bits — same order of magnitude/encoding choice as RegisterOAuthClientService's own
  // identical secret generation.
  private static final int SECRET_LENGTH = 32;

  private final WebhookEndpointRepository endpoints;
  private final OrganizationExistsChecker orgExistsChecker;
  private final WebhookSigningSecretCipher cipher;
  private final AuditEventRecorder auditEvents;
  private final SecureRandom secureRandom = new SecureRandom();

  public RegisterWebhookEndpointService(
      final WebhookEndpointRepository endpoints,
      final OrganizationExistsChecker orgExistsChecker,
      final WebhookSigningSecretCipher cipher,
      final AuditEventRecorder auditEvents) {
    this.endpoints = endpoints;
    this.orgExistsChecker = orgExistsChecker;
    this.cipher = cipher;
    this.auditEvents = auditEvents;
  }

  @Override
  public RegisterWebhookEndpointResult handle(final RegisterWebhookEndpointCommand command) {
    // BR-ORG-02 (this module's own equivalent): never let an endpoint be registered under a
    // non-existent Organization — same reasoning RegisterOAuthClientService's own identical check
    // already establishes, including why it must be an application-layer check (no FK enforces
    // this across modules; cross-module migration ordering isn't guaranteed).
    if (!orgExistsChecker.exists(command.organizationId())) {
      throw new OrganizationNotFoundException(command.organizationId());
    }

    final String rawSecret = generateRawSecret();
    final WebhookEndpoint endpoint =
        WebhookEndpoint.register(
            command.organizationId(),
            command.url(),
            command.description(),
            command.subscribedEventTypes(),
            cipher.encrypt(rawSecret));

    endpoints.save(endpoint);
    auditEvents.write(
        command.actor(),
        "webhook_endpoint.registered",
        "WebhookEndpoint",
        endpoint.id().toString(),
        "organizationId=" + command.organizationId());
    return new RegisterWebhookEndpointResult(endpoint, rawSecret);
  }

  private String generateRawSecret() {
    final byte[] bytes = new byte[SECRET_LENGTH];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
