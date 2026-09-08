package com.clavaris.organization.infrastructure.adapter.out.persistence;

import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Implements {@link EventOutboxWriter} (ADR-0007 §1, TD-ARCH-007) — identity-module's own {@code
 * JpaEventOutboxWriter} mirror, writing to this module's own, separate {@code
 * organization_event_outbox} table. Module-prefixed class name, same collision reason as {@link
 * OrganizationEventOutboxEntity}'s own Javadoc. Write-only until webhook-module's dispatcher
 * exists.
 *
 * <p>Reads the current distributed-tracing id off SLF4J's own MDC — same "MDC, not an injected
 * {@code Tracer} bean" reasoning as identity-module's own {@code JpaEventOutboxWriter}.
 *
 * <p>TD-PERF-019: calls {@link EntityManager#persist} directly, not {@code
 * SpringDataOrganizationEventOutboxJpaRepository#save} — same insert-only reasoning as
 * identity-module's own {@code JpaEventOutboxWriter}, including the same {@code @Transactional} on
 * {@code write} itself (a raw {@code persist} call needs one already open on the current thread,
 * unlike {@code SimpleJpaRepository#save}'s own built-in one).
 */
@Repository
class JpaOrganizationEventOutboxWriter implements EventOutboxWriter {

  private final ObjectMapper objectMapper;
  private final EntityManager entityManager;

  // Constructed only by Spring's own component scan (via @Repository above) — EventOutboxWriter
  // (the port) is the only type callers outside this package should depend on.
  /* package */ JpaOrganizationEventOutboxWriter(
      final ObjectMapper objectMapper, final EntityManager entityManager) {
    this.objectMapper = objectMapper;
    this.entityManager = entityManager;
  }

  @Override
  @Transactional
  public void write(
      final String aggregateType,
      final String eventType,
      final UUID aggregateId,
      final UUID organizationId,
      final Object payload) {
    // Jackson 3: writeValueAsString throws the unchecked JacksonException, not a checked
    // JsonProcessingException (Jackson 2's API) — same rationale as identity-module's own
    // identical catch block.
    final String serializedPayload;
    try {
      serializedPayload = objectMapper.writeValueAsString(payload);
    } catch (JacksonException e) {
      throw new IllegalStateException(
          "Failed to serialize outbox payload for event " + eventType, e);
    }

    // No explicit saveAndFlush here — this write participates in the same @Transactional as the
    // Organization delete itself (ADR-0007 §1: same transaction, not same statement), so it's
    // fine for Hibernate to flush it whenever the transaction commits.
    entityManager.persist(
        new OrganizationEventOutboxEntity(
            UUID.randomUUID(),
            organizationId,
            aggregateType,
            aggregateId,
            eventType,
            serializedPayload,
            MDC.get("traceId"),
            Instant.now()));
  }
}
