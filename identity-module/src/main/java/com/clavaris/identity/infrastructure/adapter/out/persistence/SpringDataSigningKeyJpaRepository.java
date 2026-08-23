package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataSigningKeyJpaRepository extends JpaRepository<SigningKeyEntity, UUID> {

  Optional<SigningKeyEntity> findFirstByOrganizationIdAndRetiredAtIsNull(UUID organizationId);

  // TD-SEC-008: retiredAt IS NULL (the active key) OR retiredAt > retiredAfter (still within the
  // overlap window) — an explicit @Query, not a derived findByXAndYOrZ method name: Spring Data's
  // own derivation parses "AndYOrZ" as (X AND Y) OR Z, left to right, with no way to express the
  // (X AND (Y OR Z)) grouping this actually needs — a derived name here would silently leak every
  // OTHER Organization's retired-but-still-in-window keys into this Organization's own JWKS
  // response, a real cross-tenant key material leak, not a cosmetic bug.
  @Query(
      "SELECT e FROM SigningKeyEntity e WHERE e.organizationId = :organizationId "
          + "AND (e.retiredAt IS NULL OR e.retiredAt > :retiredAfter)")
  List<SigningKeyEntity> findActiveAndRetiredSince(
      @Param("organizationId") UUID organizationId, @Param("retiredAfter") Instant retiredAfter);
}
