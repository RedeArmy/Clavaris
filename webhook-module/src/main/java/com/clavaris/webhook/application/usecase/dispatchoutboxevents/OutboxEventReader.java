package com.clavaris.webhook.application.usecase.dispatchoutboxevents;

import java.util.List;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaOutboxEventReader}, which reads both producer modules'
 * own physical outbox tables via this module's own read-side JPA entities (ADR-0007 §1). {@code
 * claimUnpublishedBatch} uses {@code SELECT ... FOR UPDATE SKIP LOCKED} — safe for more than one
 * dispatcher instance polling concurrently, ADR-0007 §1's own NFR concurrency note.
 */
public interface OutboxEventReader {

  List<OutboxEvent> claimUnpublishedBatch(int limitPerSource);

  void markPublished(OutboxEvent event);
}
