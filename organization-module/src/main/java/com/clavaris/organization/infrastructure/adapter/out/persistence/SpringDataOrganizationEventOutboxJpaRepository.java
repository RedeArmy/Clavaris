package com.clavaris.organization.infrastructure.adapter.out.persistence;

import com.clavaris.common.infrastructure.adapter.out.persistence.EventOutboxRetentionRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// Module-prefixed, same collision reason as OrganizationEventOutboxEntity's own Javadoc — a
// bare SpringDataEventOutboxJpaRepository would collide with identity-module's own identically
// named interface once both are component-scanned into the same app context.
interface SpringDataOrganizationEventOutboxJpaRepository
    extends JpaRepository<OrganizationEventOutboxEntity, UUID>, EventOutboxRetentionRepository {

  // Same rationale as identity-module's own identical pair of methods (EventOutboxRetentionJob).
  @Override
  long countByOccurredAtBeforeAndPublishedAtIsNull(Instant cutoff);

  @Override
  @Modifying
  @Query("delete from OrganizationEventOutboxEntity e where e.occurredAt < :cutoff")
  long deleteByOccurredAtBefore(@Param("cutoff") Instant cutoff);
}
