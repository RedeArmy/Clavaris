package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Implements {@link EventOutboxWriter} (ADR-0007 §1) — write-only until webhook-module's dispatcher
 * exists.
 */
@Repository
class JpaEventOutboxWriter implements EventOutboxWriter {

  private final SpringDataEventOutboxJpaRepository outbox;
  private final ObjectMapper objectMapper;

  // Constructed only by Spring's own component scan (via @Repository above) — EventOutboxWriter
  // (the port) is the only type callers outside this package should depend on.
  /* package */ JpaEventOutboxWriter(
      final SpringDataEventOutboxJpaRepository outbox, final ObjectMapper objectMapper) {
    this.outbox = outbox;
    this.objectMapper = objectMapper;
  }

  @Override
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
    outbox.save(
        new EventOutboxEntity(
            UUID.randomUUID(),
            organizationId.value(),
            "Account",
            aggregateId.value(),
            eventType,
            serializedPayload,
            Instant.now()));
  }
}
