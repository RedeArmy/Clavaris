package com.clavaris.organization.domain.event;

import com.clavaris.organization.domain.model.Organization;
import java.time.Instant;
import java.util.UUID;

/**
 * BR-DATA-02/03's own organization-level equivalent of identity-module's {@code
 * AccountDeletedEvent} — TD-ARCH-007 (SDE-III review, 2026-08-26): {@code DeleteAccountService}'s
 * own outbox write had no sibling here despite organization deletion being, if anything, the more
 * consequential event for a future webhook consumer (an entire tenant's whole account pool, not one
 * identity). Written to this module's own {@code event_outbox} table (ADR-0007 §1, migration {@code
 * V20260826120000}) — a genuinely separate table from identity-module's own, not a cross-module
 * write; see {@link
 * com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter}'s own Javadoc
 * for why. {@code name} is captured here, not re-read after the fact — same "last point this field
 * is still available" reasoning as {@code AccountDeletedEvent}'s own {@code email}.
 */
public record OrganizationDeletedEvent(UUID organizationId, String name, Instant occurredAt) {

  public static OrganizationDeletedEvent from(final Organization organization) {
    return new OrganizationDeletedEvent(organization.id(), organization.name(), Instant.now());
  }
}
