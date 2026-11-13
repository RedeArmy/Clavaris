package com.clavaris.webhook.application.usecase.deliverpendingwebhooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookSigningSecretCipher;
import com.clavaris.webhook.domain.model.WebhookDelivery;
import com.clavaris.webhook.domain.model.WebhookDeliveryStatus;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DeliverPendingWebhooksServiceTest {

  private static final int MAX_ATTEMPTS = 3;
  private static final int THRESHOLD = 5;

  private final WebhookDeliveryRepository deliveries = mock(WebhookDeliveryRepository.class);
  private final WebhookEndpointRepository endpoints = mock(WebhookEndpointRepository.class);
  private final WebhookSigningSecretCipher cipher = mock(WebhookSigningSecretCipher.class);
  private final WebhookHttpSender sender = mock(WebhookHttpSender.class);
  // TD-PERF-004: a real, small bounded pool — deliverDueDeliveries() itself still blocks until
  // every task finishes, so every assertion below (made after that call returns) is exactly as
  // deterministic as when this class dispatched sequentially.
  private final ExecutorService deliveryExecutor = Executors.newFixedThreadPool(2);
  private final DeliverPendingWebhooksService service =
      new DeliverPendingWebhooksService(
          deliveries, endpoints, cipher, sender, deliveryExecutor, 50, MAX_ATTEMPTS, THRESHOLD, 2);

  @Test
  void aSuccessfulDeliveryIsRecordedAsSucceededAndNeverRetried() {
    WebhookEndpoint endpoint = registeredEndpoint();
    WebhookDelivery delivery = scheduledDelivery(endpoint.id());
    when(deliveries.claimDueBatch(50)).thenReturn(List.of(delivery));
    when(endpoints.findAllByIds(anyCollection())).thenReturn(List.of(endpoint));
    when(cipher.decrypt(any(), any())).thenReturn("raw-secret");
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
    when(endpoints.findAllByIds(anyCollection())).thenReturn(List.of(endpoint));
    when(cipher.decrypt(any(), any())).thenReturn("raw-secret");
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
    when(endpoints.findAllByIds(anyCollection())).thenReturn(List.of(endpoint));
    when(cipher.decrypt(any(), any())).thenReturn("raw-secret");
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
    when(endpoints.findAllByIds(anyCollection())).thenReturn(List.of(endpoint));
    when(cipher.decrypt(any(), eq("new-encrypted"))).thenReturn("new-raw");
    when(cipher.decrypt(any(), eq("old-encrypted"))).thenReturn("old-raw");
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
    when(endpoints.findAllByIds(anyCollection())).thenReturn(List.of(endpoint));
    when(cipher.decrypt(any(), any())).thenReturn("raw-secret");
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
    when(endpoints.findAllByIds(anyCollection())).thenReturn(List.of(endpoint));
    when(cipher.decrypt(any(), any())).thenReturn("raw-secret");
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
    when(endpoints.findAllByIds(anyCollection())).thenReturn(List.of());

    service.deliverDueDeliveries();

    WebhookDelivery saved = captureSaved();
    assertThat(saved.status()).isEqualTo(WebhookDeliveryStatus.EXHAUSTED);
  }

  // TD-ARCH-025: a delivery already claimed before its own endpoint was deactivated used to still
  // fire — DeactivateWebhookEndpointService's own former Javadoc claimed this was already
  // impossible ("simply excluded from the dispatcher"), true only for NEW fan-out. This proves the
  // fix: no HTTP call is made, and the delivery fails terminally rather than retrying.
  @Test
  void aDeactivatedEndpointFailsTheDeliveryWithoutSchedulingAFutureRetryOrCallingTheSender() {
    WebhookEndpoint deactivatedEndpoint = registeredEndpoint().deactivate();
    WebhookDelivery delivery = scheduledDelivery(deactivatedEndpoint.id());
    when(deliveries.claimDueBatch(50)).thenReturn(List.of(delivery));
    when(endpoints.findAllByIds(anyCollection())).thenReturn(List.of(deactivatedEndpoint));

    service.deliverDueDeliveries();

    WebhookDelivery saved = captureSaved();
    assertThat(saved.status()).isEqualTo(WebhookDeliveryStatus.EXHAUSTED);
    verifyNoInteractions(sender);
  }

  // SDE-III review, 2026-09-15 — real N+1 this test guards against: attemptOneDelivery used to
  // call endpoints.findById(delivery.endpointId()) once per claimed delivery, even when several
  // deliveries in the same tick target the same endpoint (a burst fanned out to one subscriber, or
  // several retries queued together) — up to batchSize individual SELECTs per tick. Three
  // deliveries against the same endpoint must now still cost exactly one findAllByIds call, never
  // three, and findById must never be called at all from this code path anymore.
  @Test
  void severalDeliveriesAgainstTheSameEndpointFetchItWithOneBatchCallNotOnePerDelivery() {
    WebhookEndpoint endpoint = registeredEndpoint();
    WebhookDelivery deliveryOne = scheduledDelivery(endpoint.id());
    WebhookDelivery deliveryTwo = scheduledDelivery(endpoint.id());
    WebhookDelivery deliveryThree = scheduledDelivery(endpoint.id());
    when(deliveries.claimDueBatch(50)).thenReturn(List.of(deliveryOne, deliveryTwo, deliveryThree));
    when(endpoints.findAllByIds(anyCollection())).thenReturn(List.of(endpoint));
    when(cipher.decrypt(any(), any())).thenReturn("raw-secret");
    when(sender.send(eq(endpoint.url()), anyMap(), anyString()))
        .thenReturn(new WebhookDeliveryOutcome(true, 200, null));

    service.deliverDueDeliveries();

    verify(endpoints, times(1)).findAllByIds(anyCollection());
    verify(endpoints, never()).findById(any());
    verify(deliveries, times(3)).save(any());
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
    // Both endpoints come back from the one batch-fetch call this test's own delivery batch
    // triggers — see DeliverPendingWebhooksService's own Javadoc for why that's now one query,
    // not one per delivery.
    when(endpoints.findAllByIds(anyCollection()))
        .thenReturn(List.of(brokenEndpoint, healthyEndpoint));
    when(cipher.decrypt(any(), eq("undecryptable-secret")))
        .thenThrow(new IllegalStateException("key rotated, old secret no longer decryptable"));
    when(cipher.decrypt(any(), eq("encrypted-secret"))).thenReturn("raw-secret");
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

  // ---- TD-ARCH-053 ----

  private DeliverPendingWebhooksService serviceWith(
      final ExecutorService executor, final int maxInFlightPerEndpoint) {
    return new DeliverPendingWebhooksService(
        deliveries,
        endpoints,
        cipher,
        sender,
        executor,
        50,
        MAX_ATTEMPTS,
        THRESHOLD,
        maxInFlightPerEndpoint);
  }

  private WebhookDelivery onItsLastAttempt(final UUID endpointId) {
    return scheduledDelivery(endpointId)
        .recordFailure(500, "e1", Instant.now(), Instant.now())
        .recordFailure(500, "e2", Instant.now(), Instant.now());
  }

  @Test
  void aDeliveryThatUsesUpItsLastAttemptCountsAgainstTheEndpointWithTheConfiguredThreshold() {
    WebhookEndpoint endpoint = registeredEndpoint();
    when(deliveries.claimDueBatch(50)).thenReturn(List.of(onItsLastAttempt(endpoint.id())));
    when(endpoints.findAllByIds(anyCollection())).thenReturn(List.of(endpoint));
    when(cipher.decrypt(any(), any())).thenReturn("raw-secret");
    when(sender.send(any(), anyMap(), anyString()))
        .thenReturn(new WebhookDeliveryOutcome(false, 500, "boom"));

    service.deliverDueDeliveries();

    verify(endpoints).recordExhaustedDelivery(eq(endpoint.id()), eq(THRESHOLD), any());
  }

  @Test
  void aFailureThatWillBeRetriedDoesNotCountAgainstTheEndpoint() {
    WebhookEndpoint endpoint = registeredEndpoint();
    when(deliveries.claimDueBatch(50)).thenReturn(List.of(scheduledDelivery(endpoint.id())));
    when(endpoints.findAllByIds(anyCollection())).thenReturn(List.of(endpoint));
    when(cipher.decrypt(any(), any())).thenReturn("raw-secret");
    when(sender.send(any(), anyMap(), anyString()))
        .thenReturn(new WebhookDeliveryOutcome(false, 500, "boom"));

    service.deliverDueDeliveries();

    verify(endpoints, never()).recordExhaustedDelivery(any(), anyInt(), any());
  }

  @Test
  void aSuccessResetsTheRunOfFailuresOnlyWhenThereWasOne() {
    WebhookEndpoint healthy = registeredEndpoint();
    WebhookEndpoint struggling =
        WebhookEndpoint.reconstitute(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "https://struggling.example.com/hook",
            null,
            List.of("account.created"),
            "encrypted-secret",
            null,
            null,
            true,
            Instant.now(),
            3,
            null);
    when(deliveries.claimDueBatch(50))
        .thenReturn(List.of(scheduledDelivery(healthy.id()), scheduledDelivery(struggling.id())));
    when(endpoints.findAllByIds(anyCollection())).thenReturn(List.of(healthy, struggling));
    when(cipher.decrypt(any(), any())).thenReturn("raw-secret");
    when(sender.send(any(), anyMap(), anyString()))
        .thenReturn(new WebhookDeliveryOutcome(true, 200, null));

    service.deliverDueDeliveries();

    verify(endpoints).resetConsecutiveFailures(struggling.id());
    verify(endpoints, never()).resetConsecutiveFailures(healthy.id());
  }

  @Test
  void anEndpointThatStopsAnsweringIsNotSentTheRestOfItsDeliveriesThisTick() {
    WebhookEndpoint endpoint = registeredEndpoint();
    when(deliveries.claimDueBatch(50))
        .thenReturn(
            List.of(
                scheduledDelivery(endpoint.id()),
                scheduledDelivery(endpoint.id()),
                scheduledDelivery(endpoint.id())));
    when(endpoints.findAllByIds(anyCollection())).thenReturn(List.of(endpoint));
    when(cipher.decrypt(any(), any())).thenReturn("raw-secret");
    when(sender.send(any(), anyMap(), anyString()))
        .thenReturn(new WebhookDeliveryOutcome(false, null, "timed out"));

    serviceWith(deliveryExecutor, 1).deliverDueDeliveries();

    // One send, one recorded failure; the other two keep the lease the claim gave them and no
    // attempt is counted for them.
    verify(sender, times(1)).send(any(), anyMap(), anyString());
    verify(deliveries, times(1)).save(any());
  }

  @Test
  void aClientErrorDoesNotStopTheOtherDeliveriesOfTheSameEndpoint() {
    WebhookEndpoint endpoint = registeredEndpoint();
    when(deliveries.claimDueBatch(50))
        .thenReturn(List.of(scheduledDelivery(endpoint.id()), scheduledDelivery(endpoint.id())));
    when(endpoints.findAllByIds(anyCollection())).thenReturn(List.of(endpoint));
    when(cipher.decrypt(any(), any())).thenReturn("raw-secret");
    when(sender.send(any(), anyMap(), anyString()))
        .thenReturn(new WebhookDeliveryOutcome(false, 400, "rejected this payload"));

    serviceWith(deliveryExecutor, 1).deliverDueDeliveries();

    verify(sender, times(2)).send(any(), anyMap(), anyString());
  }

  @Test
  void onceTheEndpointIsSwitchedOffItsRemainingDeliveriesAreNotSent() {
    WebhookEndpoint endpoint = registeredEndpoint();
    when(deliveries.claimDueBatch(50))
        .thenReturn(List.of(onItsLastAttempt(endpoint.id()), onItsLastAttempt(endpoint.id())));
    when(endpoints.findAllByIds(anyCollection())).thenReturn(List.of(endpoint));
    when(endpoints.recordExhaustedDelivery(any(), anyInt(), any())).thenReturn(true);
    when(cipher.decrypt(any(), any())).thenReturn("raw-secret");
    when(sender.send(any(), anyMap(), anyString()))
        .thenReturn(new WebhookDeliveryOutcome(false, 400, "rejected"));

    serviceWith(deliveryExecutor, 1).deliverDueDeliveries();

    verify(sender, times(1)).send(any(), anyMap(), anyString());
  }

  // The cap: with room for six threads and four deliveries to one endpoint, never more than two are
  // in flight. The barrier makes each pair wait for the other, so reaching two proves the lanes run
  // side by side, and the maximum proves a third never joins them.
  @Test
  void neverMoreThanTwoDeliveriesToOneEndpointAreInFlightAtOnce() {
    WebhookEndpoint endpoint = registeredEndpoint();
    when(deliveries.claimDueBatch(50))
        .thenReturn(
            List.of(
                scheduledDelivery(endpoint.id()),
                scheduledDelivery(endpoint.id()),
                scheduledDelivery(endpoint.id()),
                scheduledDelivery(endpoint.id())));
    when(endpoints.findAllByIds(anyCollection())).thenReturn(List.of(endpoint));
    when(cipher.decrypt(any(), any())).thenReturn("raw-secret");
    final CyclicBarrier pair = new CyclicBarrier(2);
    final AtomicInteger inFlight = new AtomicInteger();
    final AtomicInteger maxInFlight = new AtomicInteger();
    when(sender.send(any(), anyMap(), anyString()))
        .thenAnswer(
            _ -> {
              maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
              try {
                pair.await(10, TimeUnit.SECONDS);
              } catch (BrokenBarrierException | TimeoutException e) {
                throw new IllegalStateException("the two lanes did not meet", e);
              } finally {
                inFlight.decrementAndGet();
              }
              return new WebhookDeliveryOutcome(true, 200, null);
            });
    final ExecutorService wide = Executors.newFixedThreadPool(6);
    try {
      serviceWith(wide, 2).deliverDueDeliveries();
    } finally {
      wide.shutdownNow();
    }

    assertThat(maxInFlight.get()).isEqualTo(2);
    verify(sender, times(4)).send(any(), anyMap(), anyString());
  }

  @Test
  void theThresholdAndTheCapMustBeAtLeastOne() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new DeliverPendingWebhooksService(
                    deliveries, endpoints, cipher, sender, deliveryExecutor, 50, 3, 0, 2));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new DeliverPendingWebhooksService(
                    deliveries, endpoints, cipher, sender, deliveryExecutor, 50, 3, 5, 0));
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
