package com.clavaris.webhook.infrastructure.adapter.out.persistence;

import com.clavaris.common.infrastructure.adapter.out.persistence.AbstractEventOutboxEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A read-side (and {@code published_at}-write-side) mapping onto identity-module's own {@code
 * event_outbox} table — deliberately not a dependency on identity-module's own {@code
 * EventOutboxEntity} Java type; this module only ever depends on the physical table shape (a data
 * contract, ADR-0007 §1's own explicit boundary), never another module's internal types. Same
 * "physically shared table, independently-owned Java mapping per consumer" precedent
 * organization-module's own {@code OrganizationEventOutboxEntity} already establishes relative to
 * identity-module's own table.
 *
 * <p>Columns themselves live on {@link AbstractEventOutboxEntity} (common module) — the same shared
 * superclass identity-module's own {@code EventOutboxEntity} and organization-module's own {@code
 * OrganizationEventOutboxEntity} already extend; this class and its own sibling ({@link
 * OrganizationOutboxRowEntity}) reuse it too rather than re-duplicating the same seven columns a
 * third and fourth time (SonarCloud-flagged duplication between this pair, closed by extending the
 * existing superclass instead of introducing a new webhook-module-local one).
 */
@Entity
@Table(name = "event_outbox")
public class IdentityOutboxRowEntity extends AbstractEventOutboxEntity {

  protected IdentityOutboxRowEntity() {
    super();
  }
}
