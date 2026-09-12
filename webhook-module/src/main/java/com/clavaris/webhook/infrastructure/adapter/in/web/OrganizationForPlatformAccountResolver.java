package com.clavaris.webhook.infrastructure.adapter.in.web;

import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — implemented in {@code app}. Same shape/rationale as organization-module's/
 * client-registry-module's own identically-named ports (a narrow, name-only cross-module read, not
 * the full {@code Organization} aggregate) — deliberately a SEPARATE interface, not a shared one:
 * this module cannot depend on either of theirs.
 */
@FunctionalInterface
public interface OrganizationForPlatformAccountResolver {

  @SuppressWarnings("PMD.LongVariable")
  Optional<String> resolveName(UUID organizationId, UUID ownerPlatformAccountId);
}
