package com.clavaris.organization.application.usecase.deleteorganization;

import java.util.UUID;

/**
 * Outbound port — TD-ARCH-007 (SDE-III review, 2026-08-26), organization-module's own mirror of
 * identity-module's {@code registeraccount.EventOutboxWriter}. Deliberately a separate type, not a
 * shared one in {@code common}: the two modules never share a persistence context (each has its own
 * isolated {@code event_outbox} table, own migration — see this event's own Javadoc), so there is
 * no single implementation that could honor both without one module depending on the other's
 * infrastructure. ADR-0007 §1: the row this writes must land in the SAME database transaction as
 * the domain state change it records, which is why {@link DeleteOrganizationService} calls this
 * (not a direct event bus publish) from inside its own {@code @Transactional} method. Write-only in
 * this slice: the dispatcher that drains {@code event_outbox} and actually delivers webhooks
 * belongs to {@code webhook-module} (ADR-0007, 🟡 proposed), not yet built.
 */
@FunctionalInterface
public interface EventOutboxWriter {

  void write(String eventType, UUID aggregateId, Object payload);
}
