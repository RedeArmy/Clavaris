package com.clavaris.webhook.application.usecase.replaywebhookdelivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.webhook.application.usecase.deliverpendingwebhooks.WebhookDeliveryRepository;
import com.clavaris.webhook.domain.model.WebhookDelivery;
import com.clavaris.webhook.domain.model.WebhookDeliveryStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReplayWebhookDeliveryServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private final WebhookDeliveryRepository deliveries = mock(WebhookDeliveryRepository.class);
  private final AuditEventRecorder auditEvents = mock(AuditEventRecorder.class);
  private final ReplayWebhookDeliveryService service =
      new ReplayWebhookDeliveryService(deliveries, auditEvents);

  @Test
  void resetsAnExhaustedDeliveryBackToPendingAndAuditsIt() {
    UUID endpointId = UUID.randomUUID();
    WebhookDelivery exhausted =
        WebhookDelivery.schedule(
                endpointId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Account",
                UUID.randomUUID(),
                "account.created",
                "{}",
                null)
            .recordFailure(500, "boom", Instant.now(), null);
    when(deliveries.findById(exhausted.id())).thenReturn(Optional.of(exhausted));

    WebhookDelivery result =
        service.handle(new ReplayWebhookDeliveryCommand(endpointId, exhausted.id(), ACTOR));

    assertThat(result.status()).isEqualTo(WebhookDeliveryStatus.PENDING);
    verify(deliveries).save(result);
    verify(auditEvents)
        .write(
            ACTOR, "webhook_delivery.replayed", "WebhookDelivery", exhausted.id().toString(), null);
  }

  @Test
  void rejectsReplayOfAnUnknownDelivery() {
    UUID unknownId = UUID.randomUUID();
    when(deliveries.findById(unknownId)).thenReturn(Optional.empty());
    ReplayWebhookDeliveryCommand command =
        new ReplayWebhookDeliveryCommand(UUID.randomUUID(), unknownId, ACTOR);

    assertThatExceptionOfType(WebhookDeliveryNotFoundException.class)
        .isThrownBy(() -> service.handle(command));
  }

  @Test
  void rejectsReplayWhenTheDeliveryBelongsToADifferentEndpoint() {
    // Real bug this test guards against (SDE-III review, 2026-09-03): the {endpointId} path
    // segment was accepted but never checked against the delivery actually being replayed — a
    // deliveryId belonging to a different endpoint (or a different Organization entirely) still
    // replayed.
    WebhookDelivery succeeded =
        WebhookDelivery.schedule(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Account",
                UUID.randomUUID(),
                "account.created",
                "{}",
                null)
            .recordSuccess(200, Instant.now());
    when(deliveries.findById(succeeded.id())).thenReturn(Optional.of(succeeded));
    UUID someOtherEndpointId = UUID.randomUUID();
    ReplayWebhookDeliveryCommand command =
        new ReplayWebhookDeliveryCommand(someOtherEndpointId, succeeded.id(), ACTOR);

    assertThatExceptionOfType(WebhookDeliveryNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(deliveries, never()).save(any());
    verifyNoInteractions(auditEvents);
  }

  @Test
  void resetsASucceededDeliveryBackToPending() {
    UUID endpointId = UUID.randomUUID();
    WebhookDelivery succeeded =
        WebhookDelivery.schedule(
                endpointId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Account",
                UUID.randomUUID(),
                "account.created",
                "{}",
                null)
            .recordSuccess(200, Instant.now());
    when(deliveries.findById(succeeded.id())).thenReturn(Optional.of(succeeded));

    WebhookDelivery result =
        service.handle(new ReplayWebhookDeliveryCommand(endpointId, succeeded.id(), ACTOR));

    assertThat(result.status()).isEqualTo(WebhookDeliveryStatus.PENDING);
  }

  @Test
  void rejectsReplayOfAPendingDelivery_stillOwnedByTheOrdinaryRetryEngine() {
    UUID endpointId = UUID.randomUUID();
    WebhookDelivery pending =
        WebhookDelivery.schedule(
            endpointId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Account",
            UUID.randomUUID(),
            "account.created",
            "{}",
            null);
    when(deliveries.findById(pending.id())).thenReturn(Optional.of(pending));
    ReplayWebhookDeliveryCommand command =
        new ReplayWebhookDeliveryCommand(endpointId, pending.id(), ACTOR);

    assertThatExceptionOfType(WebhookDeliveryNotReplayableException.class)
        .isThrownBy(() -> service.handle(command));

    verify(deliveries, never()).save(any());
    verifyNoInteractions(auditEvents);
  }

  @Test
  void rejectsReplayOfAFailedDeliveryStillAwaitingItsOwnBackoff() {
    UUID endpointId = UUID.randomUUID();
    WebhookDelivery failedNotYetDue =
        WebhookDelivery.schedule(
                endpointId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Account",
                UUID.randomUUID(),
                "account.created",
                "{}",
                null)
            .recordFailure(500, "boom", Instant.now(), Instant.now().plusSeconds(3600));
    when(deliveries.findById(failedNotYetDue.id())).thenReturn(Optional.of(failedNotYetDue));
    ReplayWebhookDeliveryCommand command =
        new ReplayWebhookDeliveryCommand(endpointId, failedNotYetDue.id(), ACTOR);

    assertThatExceptionOfType(WebhookDeliveryNotReplayableException.class)
        .isThrownBy(() -> service.handle(command));
  }
}
