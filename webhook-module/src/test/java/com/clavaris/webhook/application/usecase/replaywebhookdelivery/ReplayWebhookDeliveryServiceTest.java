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
    WebhookDelivery exhausted =
        WebhookDelivery.schedule(
                UUID.randomUUID(),
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
        service.handle(new ReplayWebhookDeliveryCommand(exhausted.id(), ACTOR));

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
    ReplayWebhookDeliveryCommand command = new ReplayWebhookDeliveryCommand(unknownId, ACTOR);

    assertThatExceptionOfType(WebhookDeliveryNotFoundException.class)
        .isThrownBy(() -> service.handle(command));
  }

  @Test
  void resetsASucceededDeliveryBackToPending() {
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

    WebhookDelivery result =
        service.handle(new ReplayWebhookDeliveryCommand(succeeded.id(), ACTOR));

    assertThat(result.status()).isEqualTo(WebhookDeliveryStatus.PENDING);
  }

  @Test
  void rejectsReplayOfAPendingDelivery_stillOwnedByTheOrdinaryRetryEngine() {
    WebhookDelivery pending =
        WebhookDelivery.schedule(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Account",
            UUID.randomUUID(),
            "account.created",
            "{}",
            null);
    when(deliveries.findById(pending.id())).thenReturn(Optional.of(pending));
    ReplayWebhookDeliveryCommand command = new ReplayWebhookDeliveryCommand(pending.id(), ACTOR);

    assertThatExceptionOfType(WebhookDeliveryNotReplayableException.class)
        .isThrownBy(() -> service.handle(command));

    verify(deliveries, never()).save(any());
    verifyNoInteractions(auditEvents);
  }

  @Test
  void rejectsReplayOfAFailedDeliveryStillAwaitingItsOwnBackoff() {
    WebhookDelivery failedNotYetDue =
        WebhookDelivery.schedule(
                UUID.randomUUID(),
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
        new ReplayWebhookDeliveryCommand(failedNotYetDue.id(), ACTOR);

    assertThatExceptionOfType(WebhookDeliveryNotReplayableException.class)
        .isThrownBy(() -> service.handle(command));
  }
}
