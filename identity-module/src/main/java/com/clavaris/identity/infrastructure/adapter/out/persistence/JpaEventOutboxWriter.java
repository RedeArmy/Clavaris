package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Implements {@link EventOutboxWriter} (ADR-0007 §1) — write-only until webhook-module's dispatcher
 * exists.
 *
 * <p>Reads the current distributed-tracing id off SLF4J's own MDC (see {@code
 * AbstractEventOutboxEntity}'s own Javadoc for why MDC, not an injected {@code Tracer} bean) rather
 * than accepting it as a caller parameter — every one of this port's existing call sites
 * (RegisterAccountService and every use case after it) would otherwise need to learn about tracing,
 * a cross-cutting concern this write-side adapter can absorb on its own without widening the port.
 *
 * <p>TD-PERF-019: calls {@link EntityManager#persist} directly, not {@code
 * SpringDataEventOutboxJpaRepository#save} — {@link #write} is this class's only method, mints a
 * brand-new random id every call, and nothing anywhere ever re-saves an already-written row (the
 * dispatcher-side {@code markPublishedBatch} in webhook-module updates rows via its own bulk {@code
 * UPDATE}, never through this class), so this table is genuinely insert-only from here.
 * {@code @Transactional} on {@code write} itself, same "a raw {@code persist} call needs one
 * already open on the current thread" reasoning {@code JpaWorkspaceRepository#save}'s own identical
 * annotation documents — every real caller already has one (this method's own class Javadoc above),
 * but this guards a bare test-fixture caller too, same precedent {@code
 * JpaAccountRepository#save}'s own {@code @Transactional} already established.
 */
@Repository
class JpaEventOutboxWriter implements EventOutboxWriter {

  private final ObjectMapper objectMapper;
  private final EntityManager entityManager;

  // Constructed only by Spring's own component scan (via @Repository above) — EventOutboxWriter
  // (the port) is the only type callers outside this package should depend on.
  /* package */ JpaEventOutboxWriter(
      final ObjectMapper objectMapper, final EntityManager entityManager) {
    this.objectMapper = objectMapper;
    this.entityManager = entityManager;
  }

  @Override
  @Transactional
  public void write(
      final String eventType,
      final AccountId aggregateId,
      final OrganizationId organizationId,
      final Object payload) {
    // Jackson 3: writeValueAsString throws the unchecked JacksonException, not a checked
    // JsonProcessingException (Jackson 2's API) — caught anyway, to translate it into a message
    // that names the failing event type rather than a bare stack trace.
    final String serializedPayload;
    try {
      serializedPayload = objectMapper.writeValueAsString(payload);
    } catch (JacksonException e) {
      // A payload that can't serialize is a programming error in the caller (every domain event
      // shape must be JSON-serializable by construction), not a transient/retryable condition —
      // fail loudly here rather than silently dropping the event or writing malformed JSON that
      // would only surface as a bug once the dispatcher tries to read it back.
      throw new IllegalStateException(
          "Failed to serialize outbox payload for event " + eventType, e);
    }

    // No explicit saveAndFlush here (unlike JpaAccountRepository): this write doesn't need to
    // throw synchronously — it participates in the same @Transactional as the account insert
    // (ADR-0007 §1: same transaction, not same statement), so it's fine for Hibernate to flush it
    // whenever the transaction commits.
    entityManager.persist(
        new EventOutboxEntity(
            UUID.randomUUID(),
            organizationId.value(),
            "Account",
            aggregateId.value(),
            eventType,
            serializedPayload,
            MDC.get("traceId"),
            Instant.now()));
  }
}
