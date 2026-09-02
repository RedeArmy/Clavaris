package com.clavaris.webhook.infrastructure.config;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.webhook.application.usecase.activatewebhookendpoint.ActivateWebhookEndpointService;
import com.clavaris.webhook.application.usecase.activatewebhookendpoint.ActivateWebhookEndpointUseCase;
import com.clavaris.webhook.application.usecase.deactivatewebhookendpoint.DeactivateWebhookEndpointService;
import com.clavaris.webhook.application.usecase.deactivatewebhookendpoint.DeactivateWebhookEndpointUseCase;
import com.clavaris.webhook.application.usecase.deliverpendingwebhooks.DeliverPendingWebhooksService;
import com.clavaris.webhook.application.usecase.deliverpendingwebhooks.DeliverPendingWebhooksUseCase;
import com.clavaris.webhook.application.usecase.deliverpendingwebhooks.WebhookDeliveryRepository;
import com.clavaris.webhook.application.usecase.deliverpendingwebhooks.WebhookHttpSender;
import com.clavaris.webhook.application.usecase.dispatchoutboxevents.DispatchOutboxEventsService;
import com.clavaris.webhook.application.usecase.dispatchoutboxevents.DispatchOutboxEventsUseCase;
import com.clavaris.webhook.application.usecase.dispatchoutboxevents.OutboxEventReader;
import com.clavaris.webhook.application.usecase.listwebhookdeliveriesforendpoint.ListWebhookDeliveriesForEndpointService;
import com.clavaris.webhook.application.usecase.listwebhookdeliveriesforendpoint.ListWebhookDeliveriesForEndpointUseCase;
import com.clavaris.webhook.application.usecase.listwebhookendpointsfororganization.ListWebhookEndpointsForOrganizationService;
import com.clavaris.webhook.application.usecase.listwebhookendpointsfororganization.ListWebhookEndpointsForOrganizationUseCase;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.OrganizationExistsChecker;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.RegisterWebhookEndpointService;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.RegisterWebhookEndpointUseCase;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookSigningSecretCipher;
import com.clavaris.webhook.application.usecase.replaywebhookdelivery.ReplayWebhookDeliveryService;
import com.clavaris.webhook.application.usecase.replaywebhookdelivery.ReplayWebhookDeliveryUseCase;
import com.clavaris.webhook.application.usecase.rotatewebhookendpointsecret.RotateWebhookEndpointSecretService;
import com.clavaris.webhook.application.usecase.rotatewebhookendpointsecret.RotateWebhookEndpointSecretUseCase;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires application-layer use cases to Spring's context — same rationale/shape as identity-module's
 * {@code IdentityUseCaseConfig}/organization-module's {@code OrganizationUseCaseConfig}: one
 * {@code @Bean} method per use case is this class's entire job, no business logic of its own.
 */
@Configuration
class WebhookUseCaseConfig {

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ WebhookUseCaseConfig() {
    // Intentionally empty — this class holds no state, only the @Bean methods below.
  }

  @Bean
  /* package */ RegisterWebhookEndpointUseCase registerWebhookEndpointUseCase(
      final WebhookEndpointRepository endpoints,
      final OrganizationExistsChecker orgExistsChecker,
      final WebhookSigningSecretCipher cipher,
      final AuditEventRecorder auditEvents) {
    return new RegisterWebhookEndpointService(endpoints, orgExistsChecker, cipher, auditEvents);
  }

  @Bean
  /* package */ ListWebhookEndpointsForOrganizationUseCase
      listWebhookEndpointsForOrganizationUseCase(final WebhookEndpointRepository endpoints) {
    return new ListWebhookEndpointsForOrganizationService(endpoints);
  }

  @Bean
  /* package */ RotateWebhookEndpointSecretUseCase rotateWebhookEndpointSecretUseCase(
      final WebhookEndpointRepository endpoints,
      final WebhookSigningSecretCipher cipher,
      final AuditEventRecorder auditEvents,
      // ADR-0007's own first open question, resolved: how long the outgoing secret keeps being
      // honoured alongside the new one — default 24h, matching the ADR's own example retry window
      // (long enough for an operator to update their own verification code the same day).
      @Value("${clavaris.webhook.secret-rotation-overlap:PT24H}") final Duration overlapWindow) {
    return new RotateWebhookEndpointSecretService(endpoints, cipher, auditEvents, overlapWindow);
  }

  @Bean
  /* package */ DeactivateWebhookEndpointUseCase deactivateWebhookEndpointUseCase(
      final WebhookEndpointRepository endpoints, final AuditEventRecorder auditEvents) {
    return new DeactivateWebhookEndpointService(endpoints, auditEvents);
  }

  @Bean
  /* package */ ActivateWebhookEndpointUseCase activateWebhookEndpointUseCase(
      final WebhookEndpointRepository endpoints, final AuditEventRecorder auditEvents) {
    return new ActivateWebhookEndpointService(endpoints, auditEvents);
  }

  @SuppressWarnings("PMD.LongVariable")
  @Bean
  /* package */ DispatchOutboxEventsUseCase dispatchOutboxEventsUseCase(
      final OutboxEventReader outboxEvents,
      final WebhookEndpointRepository endpoints,
      final WebhookDeliveryRepository deliveries,
      @Value("${clavaris.webhook.dispatch-batch-size-per-source:200}")
          final int batchSizePerSource) {
    return new DispatchOutboxEventsService(outboxEvents, endpoints, deliveries, batchSizePerSource);
  }

  @Bean
  /* package */ DeliverPendingWebhooksUseCase deliverPendingWebhooksUseCase(
      final WebhookDeliveryRepository deliveries,
      final WebhookEndpointRepository endpoints,
      final WebhookSigningSecretCipher cipher,
      final WebhookHttpSender sender,
      @Value("${clavaris.webhook.delivery-batch-size:50}") final int batchSize,
      // ADR-0007 §2's own "e.g. 8 attempts over 24h" example schedule.
      @Value("${clavaris.webhook.delivery-max-attempts:8}") final int maxAttempts) {
    return new DeliverPendingWebhooksService(
        deliveries, endpoints, cipher, sender, batchSize, maxAttempts);
  }

  @Bean
  /* package */ ReplayWebhookDeliveryUseCase replayWebhookDeliveryUseCase(
      final WebhookDeliveryRepository deliveries, final AuditEventRecorder auditEvents) {
    return new ReplayWebhookDeliveryService(deliveries, auditEvents);
  }

  @Bean
  /* package */ ListWebhookDeliveriesForEndpointUseCase listWebhookDeliveriesForEndpointUseCase(
      final WebhookEndpointRepository endpoints, final WebhookDeliveryRepository deliveries) {
    return new ListWebhookDeliveriesForEndpointService(endpoints, deliveries);
  }
}
