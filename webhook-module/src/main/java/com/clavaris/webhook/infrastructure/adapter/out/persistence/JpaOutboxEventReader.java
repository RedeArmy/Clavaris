package com.clavaris.webhook.infrastructure.adapter.out.persistence;

import com.clavaris.webhook.application.usecase.dispatchoutboxevents.OutboxEvent;
import com.clavaris.webhook.application.usecase.dispatchoutboxevents.OutboxEventReader;
import com.clavaris.webhook.application.usecase.dispatchoutboxevents.OutboxSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link OutboxEventReader} by reading both producer modules' own physical outbox tables
 * via this module's own read-side entities ({@link IdentityOutboxRowEntity}/{@link
 * OrganizationOutboxRowEntity}) — see either entity's own Javadoc for why these are a deliberately
 * separate, module-owned mapping rather than a dependency on
 * identity-module's/organization-module's own outbox entity types.
 */
@SuppressWarnings("PMD.LongVariable")
@Repository
class JpaOutboxEventReader implements OutboxEventReader {

  private final SpringDataIdentityOutboxRowJpaRepository identityOutbox;
  private final SpringDataOrganizationOutboxRowJpaRepository organizationOutbox;

  /* package */ JpaOutboxEventReader(
      final SpringDataIdentityOutboxRowJpaRepository identityOutbox,
      final SpringDataOrganizationOutboxRowJpaRepository organizationOutbox) {
    this.identityOutbox = identityOutbox;
    this.organizationOutbox = organizationOutbox;
  }

  // Read-only in the sense that this method itself never writes — DispatchOutboxEventsService's
  // own @Transactional (application layer) is what actually wraps this claim together with the
  // WebhookDelivery inserts and the markPublished calls that follow it into one short transaction,
  // same "no DB transaction held open across a network call" shape DeliverPendingWebhooksService's
  // own two-phase design applies to the delivery side.
  @Override
  public List<OutboxEvent> claimUnpublishedBatch(final int limitPerSource) {
    final List<OutboxEvent> claimed = new ArrayList<>();
    for (final IdentityOutboxRowEntity row : identityOutbox.claimUnpublished(limitPerSource)) {
      claimed.add(
          new OutboxEvent(
              OutboxSource.IDENTITY,
              row.getId(),
              row.getOrganizationId(),
              row.getAggregateType(),
              row.getAggregateId(),
              row.getEventType(),
              row.getPayload(),
              row.getOccurredAt()));
    }
    for (final OrganizationOutboxRowEntity row :
        organizationOutbox.claimUnpublished(limitPerSource)) {
      claimed.add(
          new OutboxEvent(
              OutboxSource.ORGANIZATION,
              row.getId(),
              row.getOrganizationId(),
              row.getAggregateType(),
              row.getAggregateId(),
              row.getEventType(),
              row.getPayload(),
              row.getOccurredAt()));
    }
    return claimed;
  }

  @Override
  @Transactional
  public void markPublished(final OutboxEvent event) {
    final Instant now = Instant.now();
    if (event.source() == OutboxSource.IDENTITY) {
      identityOutbox.markPublished(event.id(), now);
    } else {
      organizationOutbox.markPublished(event.id(), now);
    }
  }
}
