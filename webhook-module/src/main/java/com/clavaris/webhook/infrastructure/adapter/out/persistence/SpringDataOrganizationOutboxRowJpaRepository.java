package com.clavaris.webhook.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataOrganizationOutboxRowJpaRepository
    extends JpaRepository<OrganizationOutboxRowEntity, UUID> {

  @Query(
      value =
          "select * from organization_event_outbox where published_at is null order by"
              + " occurred_at asc limit :limit for update skip locked",
      nativeQuery = true)
  List<OrganizationOutboxRowEntity> claimUnpublished(@Param("limit") int limit);

  @SuppressWarnings("PMD.ShortVariable")
  @Modifying
  @Query("update OrganizationOutboxRowEntity e set e.publishedAt = :publishedAt where e.id = :id")
  void markPublished(@Param("id") UUID id, @Param("publishedAt") Instant publishedAt);
}
