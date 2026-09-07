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

  /**
   * TD-PERF-013: one bulk {@code UPDATE ... WHERE id IN (...)} per physical source table
   * represented in {@code events}, not one single-row {@code UPDATE} per event — {@code events} may
   * span both {@link OutboxSource#IDENTITY} and {@link OutboxSource#ORGANIZATION} rows in the same
   * call, since a single claimed batch routinely does; the implementation groups by source and
   * issues at most two statements total regardless of batch size.
   */
  void markPublishedBatch(List<OutboxEvent> events);
}
