package com.clavaris.identity.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Reference to an {@code Organization} (the tenant isolation boundary owned by {@code
 * organization-module}, ADR-0010) — an opaque UUID only, per the hexagonal dependency rule: no
 * module holds a live object reference across the module boundary.
 */
public record OrganizationId(UUID value) {

  public OrganizationId {
    Objects.requireNonNull(value, "OrganizationId value must not be null");
  }
}
