package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.domain.model.AccessRestrictionEntry;
import com.clavaris.organization.domain.model.RestrictionType;
import java.time.Instant;
import java.util.UUID;

@SuppressWarnings("PMD.ShortVariable")
public record AccessRestrictionEntryResponse(
    UUID id, UUID organizationId, RestrictionType type, String identifier, Instant createdAt) {

  public static AccessRestrictionEntryResponse from(final AccessRestrictionEntry entry) {
    return new AccessRestrictionEntryResponse(
        entry.id(), entry.organizationId(), entry.type(), entry.identifier(), entry.createdAt());
  }
}
