package com.clavaris.webhook.application.usecase.deliverpendingwebhooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookSigningSecretCipher;
import com.clavaris.webhook.domain.model.WebhookDelivery;
import com.clavaris.webhook.domain.model.WebhookDeliveryStatus;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DeliverPendingWebhooksServiceTest {

  private static final int MAX_ATTEMPTS = 3;

  private final WebhookDeliveryRepository deliveries = mock(WebhookDeliveryRepository.class);
  private final WebhookEndpointRepository endpoints = mock(WebhookEndpointRepository.class);
  private final WebhookSigningSecretCipher cipher = mock(WebhookSigningSecretCipher.class);
  private final WebhookHttpSender sender = mock(WebhookHttpSender.class);
  private final DeliverPendingWebhooksService service =
      new DeliverPendingWebhooksService(deliveries, endpoints, cipher, sender, 50, MAX_ATTEMPTS);

  @Test
  void aSuccessfulDeliveryIsRecordedAsSucceededAndNeverRetried() {
    WebhookEndpoint endpoint = registeredEndpoint();
    WebhookDelivery delivery = scheduledDelivery(endpoint.id());
    when(deliveries.claimDueBatch(50)).thenReturn(List.of(delivery));
    when(endpoints.findById(endpoint.id())).thenReturn(Optional.of(endpoint));
    when(cipher.decrypt(any())).thenReturn("raw-secret");
    when(sender.send(eq(endpoint.url()), anyMap(), anyString()))
        .thenReturn(new WebhookDeliveryOutcome(true, 200, null));

    service.deliverDueDeliveries();

    WebhookDelivery saved = captureSaved();
    assertThat(saved.status()).isEqualTo(WebhookDeliveryStatus.SUCCEEDED);
    assertThat(saved.lastResponseStatus()).isEqualTo(200);
    assertThat(saved.nextAttemptAt()).isNull();
  }

  @Test
  void aFailedDeliveryBelowMaxAttemptsIsScheduledForAFutureRetry() {
    WebhookEndpoint endpoint = registeredEndpoint();
    WebhookDelivery delivery = scheduledDelivery(endpoint.id());
    when(deliveries.claimDueBatch(50)).thenReturn(List.of(delivery));
    when(endpoints.findById(endpoint.id())).thenReturn(Optional.of(endpoint));
    when(cipher.decrypt(any())).thenReturn("raw-secret");
    when(sender.send(any(), anyMap(), anyString()))
        .thenReturn(new WebhookDeliveryOutcome(false, 503, "non-2xx status 503"));

    service.deliverDueDeliveries();

    WebhookDelivery saved = captureSaved();
    assertThat(saved.status()).isEqualTo(WebhookDeliveryStatus.FAILED);
    assertThat(saved.attemptCount()).isEqualTo(1);
    assertThat(saved.nextAttemptAt()).isAfter(Instant.now());
  }

  @Test
  void theFinalAllowedFailureMarksTheDeliveryExhaustedNotFailed() {
    WebhookEndpoint endpoint = registeredEndpoint();
    // Already failed MAX_ATTEMPTS - 1 times — this attempt is the last one allowed.
    WebhookDelivery delivery =
        scheduledDelivery(endpoint.id())
            .recordFailure(500, "e1", Instant.now(), Instant.now())
            .recordFailure(500, "e2", Instant.now(), Instant.now());
    when(deliveries.claimDueBatch(50)).thenReturn(List.of(delivery));
    when(endpoints.findById(endpoint.id())).thenReturn(Optional.of(endpoint));
    when(cipher.decrypt(any())).thenReturn("raw-secret");
    when(sender.send(any(), anyMap(), anyString()))
        .thenReturn(new WebhookDeliveryOutcome(false, 500, "boom"));

    service.deliverDueDeliveries();

    WebhookDelivery saved = captureSaved();
    assertThat(saved.status()).isEqualTo(WebhookDeliveryStatus.EXHAUSTED);
    assertThat(saved.attemptCount()).isEqualTo(MAX_ATTEMPTS);
    assertThat(saved.nextAttemptAt()).isNull();
  }

  @Test
  void signsWithEveryStillActiveDecryptedSecretDuringARotationOverlap() {
    WebhookEndpoint endpoint =
        WebhookEndpoint.register(
                UUID.randomUUID(), "https://example.com/hook", null, List.of("x"), "old-encrypted")
            .rotateSecret("new-encrypted", java.time.Duration.ofHours(24));
    WebhookDelivery delivery = scheduledDelivery(endpoint.id());
    when(deliveries.claimDueBatch(50)).thenReturn(List.of(delivery));
    when(endpoints.findById(endpoint.id())).thenReturn(Optional.of(endpoint));
    when(cipher.decrypt("new-encrypted")).thenReturn("new-raw");
    when(cipher.decrypt("old-encrypted")).thenReturn("old-raw");
    when(sender.send(any(), anyMap(), anyString()))
        .thenReturn(new WebhookDeliveryOutcome(true, 200, null));

    service.deliverDueDeliveries();

    ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.forClass(Map.class);
    verify(sender).send(eq(endpoint.url()), headersCaptor.capture(), anyString());
    String signatureHeader = headersCaptor.getValue().get("Clavaris-Signature");
    long v1Count = signatureHeader.split(",v1=", -1).length - 1L;
    assertThat(v1Count).isEqualTo(2);
  }

  @Test
  void sendsClavarisTraceIdHeaderWhenTheDeliveryCarriesOne() {
    WebhookEndpoint endpoint = registeredEndpoint();
    WebhookDelivery delivery = scheduledDelivery(endpoint.id());
    when(deliveries.claimDueBatch(50)).thenReturn(List.of(delivery));
    when(endpoints.findById(endpoint.id())).thenReturn(Optional.of(endpoint));
    when(cipher.decrypt(any())).thenReturn("raw-secret");
    when(sender.send(any(), anyMap(), anyString()))
        .thenReturn(new WebhookDeliveryOutcome(true, 200, null));

    service.deliverDueDeliveries();

    ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.forClass(Map.class);
    verify(sender).send(eq(endpoint.url()), headersCaptor.capture(), anyString());
    assertThat(headersCaptor.getValue()).containsEntry("Clavaris-Trace-Id", "trace-abc123");
  }

  @Test
  void omitsClavarisTraceIdHeaderWhenTheDeliveryHasNone() {
    WebhookEndpoint endpoint = registeredEndpoint();
    WebhookDelivery delivery =
        WebhookDelivery.schedule(
            endpoint.id(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Account",
            UUID.randomUUID(),
            "account.created",
            "{}",
            null);
    when(deliveries.claimDueBatch(50)).thenReturn(List.of(delivery));
    when(endpoints.findById(endpoint.id())).thenReturn(Optional.of(endpoint));
    when(cipher.decrypt(any())).thenReturn("raw-secret");
    when(sender.send(any(), anyMap(), anyString()))
        .thenReturn(new WebhookDeliveryOutcome(true, 200, null));

    service.deliverDueDeliveries();

    ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.forClass(Map.class);
    verify(sender).send(eq(endpoint.url()), headersCaptor.capture(), anyString());
    assertThat(headersCaptor.getValue()).doesNotContainKey("Clavaris-Trace-Id");
  }

  @Test
  void aMissingEndpointFailsTheDeliveryWithoutSchedulingAFutureRetry() {
    UUID vanishedEndpointId = UUID.randomUUID();
    WebhookDelivery delivery = scheduledDelivery(vanishedEndpointId);
    when(deliveries.claimDueBatch(50)).thenReturn(List.of(delivery));
    when(endpoints.findById(vanishedEndpointId)).thenReturn(Optional.empty());

    service.deliverDueDeliveries();

    WebhookDelivery saved = captureSaved();
    assertThat(saved.status()).isEqualTo(WebhookDeliveryStatus.EXHAUSTED);
  }

  // SDE-III review, 2026-09-03 — real bug this test guards against: one delivery whose secret
  // can't be decrypted used to throw uncaught out of the whole batch loop, silently skipping every
  // other already-claimed, healthy delivery — a self-inflicted partial outage for unrelated
  // consumers.
  @Test
  void oneDeliveryThrowingUnexpectedlyNeverPreventsTheRestOfTheBatchFromBeingAttempted() {
    WebhookEndpoint brokenEndpoint =
        WebhookEndpoint.register(
            UUID.randomUUID(),
            "https://broken.example.com/hook",
            null,
            List.of("account.created"),
            "undecryptable-secret");
    WebhookEndpoint healthyEndpoint = registeredEndpoint();
    WebhookDelivery brokenDelivery = scheduledDelivery(brokenEndpoint.id());
    WebhookDelivery healthyDelivery = scheduledDelivery(healthyEndpoint.id());
    when(deliveries.claimDueBatch(50)).thenReturn(List.of(brokenDelivery, healthyDelivery));
    when(endpoints.findById(brokenEndpoint.id())).thenReturn(Optional.of(brokenEndpoint));
    when(endpoints.findById(healthyEndpoint.id())).thenReturn(Optional.of(healthyEndpoint));
    when(cipher.decrypt("undecryptable-secret"))
        .thenThrow(new IllegalStateException("key rotated, old secret no longer decryptable"));
    when(cipher.decrypt("encrypted-secret")).thenReturn("raw-secret");
    when(sender.send(eq(healthyEndpoint.url()), anyMap(), anyString()))
        .thenReturn(new WebhookDeliveryOutcome(true, 200, null));

    service.deliverDueDeliveries();

    // The broken delivery is left exactly where claimDueBatch's own lease already put it — never
    // saved by this tick at all, not force-failed — see this class's own Javadoc for why that's
    // correct.
    verify(deliveries, never()).save(brokenDelivery);
    ArgumentCaptor<WebhookDelivery> savedCaptor = ArgumentCaptor.forClass(WebhookDelivery.class);
    verify(deliveries).save(savedCaptor.capture());
    assertThat(savedCaptor.getValue().id()).isEqualTo(healthyDelivery.id());
    assertThat(savedCaptor.getValue().status()).isEqualTo(WebhookDeliveryStatus.SUCCEEDED);
  }

  private WebhookEndpoint registeredEndpoint() {
    return WebhookEndpoint.register(
        UUID.randomUUID(),
        "https://example.com/hook",
        null,
        List.of("account.created"),
        "encrypted-secret");
  }

  private WebhookDelivery scheduledDelivery(final UUID endpointId) {
    return WebhookDelivery.schedule(
        endpointId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "Account",
        UUID.randomUUID(),
        "account.created",
        "{}",
        "trace-abc123");
  }

  private WebhookDelivery captureSaved() {
    ArgumentCaptor<WebhookDelivery> captor = ArgumentCaptor.forClass(WebhookDelivery.class);
    verify(deliveries).save(captor.capture());
    return captor.getValue();
  }
}
