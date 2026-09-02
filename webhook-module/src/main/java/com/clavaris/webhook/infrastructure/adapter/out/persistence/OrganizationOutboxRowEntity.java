package com.clavaris.webhook.infrastructure.adapter.out.persistence;

import com.clavaris.common.infrastructure.adapter.out.persistence.AbstractEventOutboxEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Same shape/reasoning as {@link IdentityOutboxRowEntity}, mapped onto organization-module's own
 * {@code organization_event_outbox} table instead — see that class's own Javadoc for why columns
 * live on the shared {@link AbstractEventOutboxEntity} rather than being duplicated here.
 */
@Entity
@Table(name = "organization_event_outbox")
public class OrganizationOutboxRowEntity extends AbstractEventOutboxEntity {

  protected OrganizationOutboxRowEntity() {
    super();
  }
}
