package com.clavaris.webhook.application.usecase.deliverpendingwebhooks;

import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookSigningSecretCipher;
import com.clavaris.webhook.domain.model.WebhookDelivery;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import com.clavaris.webhook.domain.service.WebhookRetrySchedule;
import com.clavaris.webhook.domain.service.WebhookSignature;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
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
 */
public class DeliverPendingWebhooksService implements DeliverPendingWebhooksUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(DeliverPendingWebhooksService.class);

  private final WebhookDeliveryRepository deliveries;
  private final WebhookEndpointRepository endpoints;
  private final WebhookSigningSecretCipher cipher;
  private final WebhookHttpSender sender;
  private final int batchSize;
  private final int maxAttempts;

  @SuppressWarnings("java:S107") // one parameter per collaborating port/operational value — same
  // rationale as AddWorkspaceMemberService's own identical suppression.
  public DeliverPendingWebhooksService(
      final WebhookDeliveryRepository deliveries,
      final WebhookEndpointRepository endpoints,
      final WebhookSigningSecretCipher cipher,
      final WebhookHttpSender sender,
      final int batchSize,
      final int maxAttempts) {
    this.deliveries = deliveries;
    this.endpoints = endpoints;
    this.cipher = cipher;
    this.sender = sender;
    this.batchSize = batchSize;
    this.maxAttempts = maxAttempts;
  }

  @Override
  public void deliverDueDeliveries() {
    for (final WebhookDelivery delivery : deliveries.claimDueBatch(batchSize)) {
      attemptOneDelivery(delivery);
    }
  }

  // PMD.GuardLogStatement false positives throughout — same rationale as every other logging call
  // site in this codebase (e.g. AddWorkspaceMemberService's own identical suppression); the values
  // logged are cheap in-memory accessors, not expensive computations the log level should gate.
  // PMD.OnlyOneReturn: the early-return for a vanished endpoint is a genuinely distinct case from
  // the rest of this method's own success/failure handling — same rationale
  // ActiveSecretsEncrypted's own identical suppression documents. PMD.LongVariable:
  // attemptCountAfterThisFailure names exactly what it is, not arbitrarily long.
  @SuppressWarnings({"PMD.GuardLogStatement", "PMD.OnlyOneReturn", "PMD.LongVariable"})
  private void attemptOneDelivery(final WebhookDelivery delivery) {
    final Optional<WebhookEndpoint> endpoint = endpoints.findById(delivery.endpointId());
    if (endpoint.isEmpty()) {
      // No hard-delete use case exists for WebhookEndpoint (deactivate/reactivate only) — this
      // should never happen in practice, but a delivery whose endpoint has vanished has nowhere
      // left to go; fail it terminally rather than retrying forever against nothing.
      LOG.warn("event=webhook_delivery_endpoint_missing deliveryId={}", delivery.id());
      deliveries.save(
          delivery.recordFailure(null, "endpoint no longer exists", Instant.now(), null));
      return;
    }

    final Instant now = Instant.now();
    final List<String> rawSecrets =
        endpoint.get().activeSecretsEncrypted(now).stream().map(cipher::decrypt).toList();
    final Map<String, String> headers =
        Map.of(
            "Content-Type", "application/json",
            "Clavaris-Signature", WebhookSignature.header(now, delivery.payload(), rawSecrets),
            "Clavaris-Event-Type", delivery.eventType(),
            "Clavaris-Event-Id", delivery.outboxEventId().toString());

    final WebhookDeliveryOutcome outcome =
        sender.send(endpoint.get().url(), headers, delivery.payload());

    if (outcome.success()) {
      LOG.info(
          "event=webhook_delivery_succeeded deliveryId={} endpointId={} statusCode={}",
          delivery.id(),
          delivery.endpointId(),
          outcome.statusCode());
      deliveries.save(delivery.recordSuccess(outcome.statusCode(), now));
      return;
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
        "event=webhook_delivery_failed deliveryId={} endpointId={} attemptCount={} exhausted={}"
            + " statusCode={}",
        delivery.id(),
        delivery.endpointId(),
        attemptCountAfterThisFailure,
        exhausted,
        outcome.statusCode());
    deliveries.save(
        delivery.recordFailure(outcome.statusCode(), outcome.errorMessage(), now, nextAttemptAt));
  }
}
