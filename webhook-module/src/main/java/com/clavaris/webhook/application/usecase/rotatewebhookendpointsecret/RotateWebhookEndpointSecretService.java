package com.clavaris.webhook.application.usecase.rotatewebhookendpointsecret;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointNotFoundException;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookSigningSecretCipher;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * Orchestration for {@link RotateWebhookEndpointSecretUseCase} — ADR-0007's own first open question
 * (signing secret rotation), resolved. See {@code WebhookEndpoint.rotateSecret}'s own Javadoc for
 * the dual-secret overlap-window mechanism this delegates to.
 */
public class RotateWebhookEndpointSecretService implements RotateWebhookEndpointSecretUseCase {

  private static final int SECRET_LENGTH = 32;

  private final WebhookEndpointRepository endpoints;
  private final WebhookSigningSecretCipher cipher;
  private final AuditEventRecorder auditEvents;
  private final Duration overlapWindow;
  private final SecureRandom secureRandom = new SecureRandom();

  @SuppressWarnings("java:S107") // one parameter per collaborating port/operational value — same
  // rationale as other multi-collaborator constructors in this codebase.
  public RotateWebhookEndpointSecretService(
      final WebhookEndpointRepository endpoints,
      final WebhookSigningSecretCipher cipher,
      final AuditEventRecorder auditEvents,
      final Duration overlapWindow) {
    this.endpoints = endpoints;
    this.cipher = cipher;
    this.auditEvents = auditEvents;
    this.overlapWindow = overlapWindow;
  }

  @Override
  public RotateWebhookEndpointSecretResult handle(
      final RotateWebhookEndpointSecretCommand command) {
    final WebhookEndpoint existing =
        endpoints
            .findById(command.endpointId())
            .orElseThrow(() -> new WebhookEndpointNotFoundException(command.endpointId()));

    final String rawNewSecret = generateRawSecret();
    final WebhookEndpoint rotated =
        existing.rotateSecret(cipher.encrypt(rawNewSecret), overlapWindow);

    endpoints.save(rotated);
    auditEvents.write(
        command.actor(),
        "webhook_endpoint.secret_rotated",
        "WebhookEndpoint",
        rotated.id().toString(),
        null);
    return new RotateWebhookEndpointSecretResult(rotated, rawNewSecret);
  }

  private String generateRawSecret() {
    final byte[] bytes = new byte[SECRET_LENGTH];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
