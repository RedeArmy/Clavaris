package com.clavaris.webhook.application.usecase.deliverpendingwebhooks;

import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookSigningSecretCipher;
import com.clavaris.webhook.domain.model.WebhookDelivery;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import com.clavaris.webhook.domain.service.WebhookRetrySchedule;
import com.clavaris.webhook.domain.service.WebhookSignature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestration for {@link DeliverPendingWebhooksUseCase} — ADR-0007 §2's own retry/signing engine.
 *
 * <p><b>Why claiming and delivering are two separate transactions, not one:</b> a real HTTP call to
 * an arbitrary, possibly-slow-or-hanging consumer endpoint must never happen while this process
 * holds a database row lock — the exact "no DB transaction held open across a network call"
 * discipline {@code AddWorkspaceMemberService}'s own Javadoc already establishes for this codebase.
 * {@link WebhookDeliveryRepository#claimDueBatch} runs its own short, lock-holding transaction that
 * immediately leases every claimed row ({@code nextAttemptAt} pushed into the near future) and
 * returns — the lock is gone by the time this method's loop below makes its first HTTP call. If
 * this process crashes mid-delivery, the lease simply expires and the row becomes claimable again
 * on a later tick: an intentional consequence of ADR-0007 §2's own "at-least-once, never
 * at-most-once" delivery guarantee, not a bug in this design.
 *
 * <p><b>SDE-III review, 2026-09-03 — real bug found and closed:</b> {@link #deliverDueDeliveries}'s
 * own loop had no per-item exception isolation — one delivery whose secret can't be decrypted (e.g.
 * after a {@code WEBHOOK_SECRET_ENCRYPTION_KEY} rotation, {@code
 * AesGcmWebhookSigningSecretCipher}'s own documented gap) threw uncaught out of {@link
 * #attemptOneDelivery}, aborting the whole tick and silently skipping every other already-claimed,
 * perfectly healthy delivery batched alongside it — a self-inflicted partial outage for unrelated
 * consumers, not a bug in the failing delivery's own retry accounting. Now catches per item: a
 * delivery whose attempt throws is logged and left exactly where {@link
 * WebhookDeliveryRepository#claimDueBatch}'s own lease already put it (this class's own Javadoc
 * above already establishes that a still-leased, not-yet-recorded row simply becomes claimable
 * again once the lease expires — the same recovery path a mid-attempt process crash already relies
 * on, not a new code path), and the loop moves on to the next delivery in the batch.
 *
 * <p><b>TD-PERF-004, 2026-09-06 — bounded concurrent dispatch:</b> {@link #deliverDueDeliveries}
 * used to make every claimed delivery's own HTTP call sequentially, one at a time — a handful of
 * slow-but-not-yet-timed-out consumer endpoints (each up to {@link
 * com.clavaris.webhook.infrastructure.adapter.out.http.JdkHttpWebhookSender}'s own 10s ceiling)
 * could occupy the calling thread for minutes per tick, head-of-line-blocking every other
 * Organization's deliveries batched alongside them, not just the slow endpoint's own. Each claimed
 * delivery's own attempt now runs as an independent task on a bounded {@link ExecutorService}
 * ({@code webhookDeliveryExecutor}, sized by {@code clavaris.webhook.delivery-concurrency}) — this
 * method still blocks until every task in the batch finishes (same "one tick fully completes before
 * {@code fixedDelay} schedules the next" semantics as before, {@link
 * com.clavaris.webhook.infrastructure.config.WebhookDispatchScheduler} relies on nothing changing
 * here), but a slow endpoint's own cost is now bounded by the pool size, not multiplied across the
 * whole batch.
 *
 * <p><b>SDE-III review, 2026-09-15 — real N+1 found and closed:</b> {@link #deliverDueDeliveries}
 * used to call {@code endpoints.findById(delivery.endpointId())} once per claimed delivery — up to
 * {@code batchSize} (50 by default) individual {@code SELECT}s every tick, even though several
 * deliveries in the same batch commonly target the same endpoint (a burst of events fanned out to
 * one subscriber, or several retries queued together). Now fetches every distinct endpoint in the
 * batch with one {@link WebhookEndpointRepository#findAllByIds} call before the per-delivery work
 * starts, the same "batch-fetch once per distinct key, not once per row" fix {@code
 * DispatchOutboxEventsService}'s own {@code findActiveByOrganizationId} memoization (TD-PERF-005)
 * already applies to this module's other N+1-shaped loop, one layer up (by Organization instead of
 * by endpoint).
 *
 * <p><b>TD-ARCH-053, 2026-11-13 — one dead endpoint can no longer hold the pool or retry
 * forever:</b> the claimed batch is grouped by endpoint and each endpoint gets at most {@code
 * maxInFlightPerEndpoint} (two) tasks, each taking that endpoint's deliveries one after another, so
 * ten slow deliveries to one URL hold two threads, not ten. The first delivery of the tick that
 * ends in a transport failure or a 5xx marks the endpoint unhealthy for the rest of the tick: its
 * remaining deliveries are not sent and simply stay under the lease {@code claimDueBatch} already
 * gave them (no attempt is counted), becoming due again when it expires. Separately, every delivery
 * that uses up all its attempts adds one to the endpoint's count of consecutive failures, and at
 * {@code autoDisableThreshold} the endpoint is switched off (BR-WEBHOOK-09) until an operator
 * reactivates it; one success resets the count.
 */
// BR-WEBHOOK-04: Clavaris reaches a consumer only through the signed webhook payload, never by
// writing to its systems.
public class DeliverPendingWebhooksService implements DeliverPendingWebhooksUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(DeliverPendingWebhooksService.class);

  private static final int HTTP_SERVER_ERROR = 500;

  private final WebhookDeliveryRepository deliveries;
  private final WebhookEndpointRepository endpoints;
  private final WebhookSigningSecretCipher cipher;
  private final WebhookHttpSender sender;
  private final ExecutorService deliveryExecutor;
  private final int batchSize;
  private final int maxAttempts;
  private final int autoDisableThreshold;
  private final int maxInFlightPerEndpoint;

  // rationale as AddWorkspaceMemberService's own identical suppression.
  public DeliverPendingWebhooksService(
      final WebhookDeliveryRepository deliveries,
      final WebhookEndpointRepository endpoints,
      final WebhookSigningSecretCipher cipher,
      final WebhookHttpSender sender,
      final ExecutorService deliveryExecutor,
      final int batchSize,
      final int maxAttempts,
      final int autoDisableThreshold,
      final int maxInFlightPerEndpoint) {
    if (autoDisableThreshold < 1 || maxInFlightPerEndpoint < 1) {
      throw new IllegalArgumentException(
          "autoDisableThreshold and maxInFlightPerEndpoint must each be at least 1");
    }
    this.deliveries = deliveries;
    this.endpoints = endpoints;
    this.cipher = cipher;
    this.sender = sender;
    this.deliveryExecutor = deliveryExecutor;
    this.batchSize = batchSize;
    this.maxAttempts = maxAttempts;
    this.autoDisableThreshold = autoDisableThreshold;
    this.maxInFlightPerEndpoint = maxInFlightPerEndpoint;
  }

  // TD-PERF-004: submits every claimed delivery's own attempt as an independent task, then blocks
  // until all of them finish — same overall "this tick is done" contract as the sequential loop it
  // replaces, just internally concurrent. Each task isolates its own failure via
  // attemptOneDeliveryIsolated (below), the same per-item isolation the sequential loop's own
  // try/catch used to provide directly — a Future.get() below is therefore not expected to ever
  // throw ExecutionException in practice, only defensively handled.
  @Override
  public void deliverDueDeliveries() {
    final List<WebhookDelivery> claimed = deliveries.claimDueBatch(batchSize);
    final Map<UUID, WebhookEndpoint> endpointsById = fetchEndpointsById(claimed);
    final Map<UUID, Queue<WebhookDelivery>> queues =
        claimed.stream()
            .collect(
                Collectors.groupingBy(
                    WebhookDelivery::endpointId,
                    LinkedHashMap::new,
                    Collectors.toCollection(ConcurrentLinkedQueue::new)));
    final List<Future<?>> tasks = new ArrayList<>();
    for (final Queue<WebhookDelivery> queue : queues.values()) {
      final AtomicBoolean unhealthy = new AtomicBoolean();
      final int lanes = Math.min(maxInFlightPerEndpoint, queue.size());
      for (int lane = 0; lane < lanes; lane++) {
        tasks.add(deliveryExecutor.submit(() -> drain(queue, unhealthy, endpointsById)));
      }
    }
    awaitAll(tasks);
  }

  // SDE-III review, 2026-09-15: one query for every distinct endpointId in the claimed batch — see
  // this class's own Javadoc for the N+1 this closes. An empty claimed batch never reaches here
  // (deliverDueDeliveries' own stream would just map over nothing), but findAllByIds handles an
  // empty id set fine regardless — no special-casing needed.
  private Map<UUID, WebhookEndpoint> fetchEndpointsById(final List<WebhookDelivery> claimed) {
    final List<UUID> endpointIds =
        claimed.stream().map(WebhookDelivery::endpointId).distinct().toList();
    return endpoints.findAllByIds(endpointIds).stream()
        .collect(Collectors.toMap(WebhookEndpoint::id, Function.identity()));
  }

  // One lane of an endpoint's deliveries: takes them one at a time until the queue is empty or a
  // delivery shows the endpoint is not answering (TD-ARCH-053). Two lanes share the queue, which is
  // what caps the endpoint's in-flight deliveries; the skipped ones keep their claim lease.
  private void drain(
      final Queue<WebhookDelivery> queue,
      final AtomicBoolean unhealthy,
      final Map<UUID, WebhookEndpoint> endpointsById) {
    while (!unhealthy.get()) {
      final WebhookDelivery next = queue.poll();
      if (next == null) {
        return;
      }
      if (!attemptOneDeliveryIsolated(next, endpointsById)) {
        unhealthy.set(true);
      }
    }
  }

  // PMD.AvoidCatchingGenericException: deliberate — see this class's own Javadoc ("SDE-III
  // review, 2026-09-03") for why a single delivery's own failure (of any kind: a decrypt error, an
  // unchecked exception from the HTTP client, anything) must never abort the rest of this batch.
  // PMD.GuardLogStatement: same false-positive rationale as attemptOneDelivery's own identical
  // suppression.
  @SuppressWarnings({
    "PMD.AvoidCatchingGenericException",
  })
  private boolean attemptOneDeliveryIsolated(
      final WebhookDelivery delivery, final Map<UUID, WebhookEndpoint> endpointsById) {
    try {
      return attemptOneDelivery(delivery, endpointsById);
    } catch (final RuntimeException e) {
      // Left exactly where claimDueBatch's own lease already put it — see this class's own
      // Javadoc for why that's the correct, already-established recovery path, not a gap this
      // catch needs to paper over with its own ad hoc recordFailure call.
      LOG.error(
          "event=webhook_delivery_attempt_failed_unexpectedly deliveryId={} endpointId={}"
              + " organizationId={} outboxEventId={} traceId={}",
          delivery.id(),
          delivery.endpointId(),
          delivery.organizationId(),
          delivery.outboxEventId(),
          delivery.traceId(),
          e);
      // Not a verdict on the endpoint (a decrypt error is ours), so the lane carries on.
      return true;
    }
  }

  // Restores the interrupt flag and stops waiting on the remaining futures rather than swallowing
  // the interrupt — standard JDK convention; already-submitted tasks keep running to completion on
  // the executor regardless (this method only stops *waiting* for them), the same "in-flight work
  // isn't cancelled, just no longer awaited by this thread" trade-off a scheduled job being
  // interrupted (e.g. application shutdown) should make.
  private static void awaitAll(final List<Future<?>> tasks) {
    for (final Future<?> task : tasks) {
      try {
        task.get();
      } catch (final InterruptedException _) {
        Thread.currentThread().interrupt();
        return;
      } catch (final ExecutionException e) {
        // Should be unreachable — attemptOneDeliveryIsolated already catches everything itself —
        // logged defensively rather than silently swallowed if that invariant is ever violated.
        LOG.error("event=webhook_delivery_task_failed_unexpectedly", e);
      }
    }
  }

  // Returns false only when the outcome says the endpoint itself is not answering, which stops the
  // rest of its deliveries this tick (TD-ARCH-053).
  private boolean attemptOneDelivery(
      final WebhookDelivery delivery, final Map<UUID, WebhookEndpoint> endpointsById) {
    final Optional<WebhookEndpoint> endpoint =
        Optional.ofNullable(endpointsById.get(delivery.endpointId()));
    if (endpoint.isEmpty()) {
      // No hard-delete use case exists for WebhookEndpoint (deactivate/reactivate only) — this
      // should never happen in practice, but a delivery whose endpoint has vanished has nowhere
      // left to go; fail it terminally rather than retrying forever against nothing.
      LOG.warn(
          "event=webhook_delivery_endpoint_missing deliveryId={} endpointId={} organizationId={}"
              + " outboxEventId={} traceId={}",
          delivery.id(),
          delivery.endpointId(),
          delivery.organizationId(),
          delivery.outboxEventId(),
          delivery.traceId());
      deliveries.save(
          delivery.recordFailure(null, "endpoint no longer exists", Instant.now(), null));
      return true;
    }

    if (!endpoint.get().active()) {
      // TD-ARCH-025: DeactivateWebhookEndpointService only excludes an endpoint from NEW fan-out
      // (findActiveByOrganizationId) — a delivery already claimed/queued before deactivation had
      // nowhere that stopped it from still firing here, contradicting that service's own former
      // "simply excluded from the dispatcher" claim. Terminal, not retried: an operator
      // deactivated this endpoint on purpose: this delivery gets no more attempts unless the
      // endpoint is reactivated and the delivery is explicitly replayed.
      LOG.warn(
          "event=webhook_delivery_endpoint_deactivated deliveryId={} endpointId={}"
              + " organizationId={} outboxEventId={} traceId={}",
          delivery.id(),
          delivery.endpointId(),
          delivery.organizationId(),
          delivery.outboxEventId(),
          delivery.traceId());
      deliveries.save(delivery.recordFailure(null, "endpoint is deactivated", Instant.now(), null));
      return true;
    }

    final Instant now = Instant.now();
    final List<String> rawSecrets =
        endpoint.get().activeSecretsEncrypted(now).stream()
            .map(encrypted -> cipher.decrypt(endpoint.get().id(), encrypted))
            .toList();
    // Clavaris-Delivery-Id: this row's own id, distinct from Clavaris-Event-Id (the outbox
    // event's own stable id, unchanged across retries and shared by every endpoint one event fans
    // out to). A consumer never needs it to dedupe — Clavaris-Event-Id already does that — but it
    // lets a consumer's own support ticket ("delivery XYZ never arrived") be handed straight to
    // GET .../webhook-endpoints/{id}/deliveries/{deliveryId} without first cross-referencing by
    // event id and timestamp.
    // Mutable map, not Map.of(...): Clavaris-Trace-Id is added conditionally below (traceId is
    // legitimately null for any delivery whose source event predates this column or wasn't
    // written on a traced thread), and Map.of(...) has no null-tolerant "put if present" form.
    final Map<String, String> headers = new HashMap<>();
    headers.put("Content-Type", "application/json");
    headers.put("Clavaris-Signature", WebhookSignature.header(now, delivery.payload(), rawSecrets));
    headers.put("Clavaris-Event-Type", delivery.eventType());
    headers.put("Clavaris-Event-Id", delivery.outboxEventId().toString());
    headers.put("Clavaris-Delivery-Id", delivery.id().toString());
    // Clavaris-Trace-Id: lets a consumer's own support ticket be cross-referenced against
    // Clavaris's own request logs for the original action that triggered this delivery, end to
    // end — SDE-III traceability review; see WebhookDelivery's own Javadoc for why this can be
    // absent.
    if (delivery.traceId() != null) {
      headers.put("Clavaris-Trace-Id", delivery.traceId());
    }

    final WebhookDeliveryOutcome outcome =
        sender.send(endpoint.get().url(), headers, delivery.payload());

    if (outcome.success()) {
      LOG.info(
          "event=webhook_delivery_succeeded deliveryId={} endpointId={} organizationId={}"
              + " outboxEventId={} eventType={} statusCode={} traceId={}",
          delivery.id(),
          delivery.endpointId(),
          delivery.organizationId(),
          delivery.outboxEventId(),
          delivery.eventType(),
          outcome.statusCode(),
          delivery.traceId());
      deliveries.save(delivery.recordSuccess(outcome.statusCode(), now));
      // The loaded count is only a hint that there is something to reset; skipping the write when
      // it was zero keeps a healthy endpoint at one statement per delivery, as before.
      if (endpoint.get().consecutiveFailures() > 0) {
        endpoints.resetConsecutiveFailures(endpoint.get().id());
      }
      return true;
    }

    final int attemptCountAfterThisFailure = delivery.attemptCount() + 1;
    final boolean exhausted = attemptCountAfterThisFailure >= maxAttempts;
    // java:S2245: ThreadLocalRandom is not cryptographically secure, but nothing here needs it to
    // be — this only spreads retry timing across endpoints that all failed at the same moment
    // (WebhookRetrySchedule's own Javadoc), never a security-sensitive decision. SecureRandom
    // would be the wrong tool here (slower, and its extra unpredictability buys nothing this
    // jitter actually needs), same "right primitive for a non-security random need" reasoning as
    // every genuinely security-sensitive random value elsewhere in this codebase (token/secret
    // generation) deliberately using SecureRandom instead.
    @SuppressWarnings("java:S2245")
    final double jitterFactor = ThreadLocalRandom.current().nextDouble(0.8, 1.2);
    final Instant nextAttemptAt =
        exhausted
            ? null
            : now.plus(WebhookRetrySchedule.nextDelay(attemptCountAfterThisFailure, jitterFactor));
    LOG.warn(
        "event=webhook_delivery_failed deliveryId={} endpointId={} organizationId={}"
            + " outboxEventId={} eventType={} attemptCount={} exhausted={} statusCode={}"
            + " errorMessage={} traceId={}",
        delivery.id(),
        delivery.endpointId(),
        delivery.organizationId(),
        delivery.outboxEventId(),
        delivery.eventType(),
        attemptCountAfterThisFailure,
        exhausted,
        outcome.statusCode(),
        outcome.errorMessage(),
        delivery.traceId());
    deliveries.save(
        delivery.recordFailure(outcome.statusCode(), outcome.errorMessage(), now, nextAttemptAt));
    if (exhausted
        && endpoints.recordExhaustedDelivery(delivery.endpointId(), autoDisableThreshold, now)) {
      LOG.warn(
          "event=webhook_endpoint_auto_disabled endpointId={} organizationId={} threshold={}",
          delivery.endpointId(),
          delivery.organizationId(),
          autoDisableThreshold);
      return false;
    }
    return outcome.statusCode() != null && outcome.statusCode() < HTTP_SERVER_ERROR;
  }
}
