package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.PlatformAccountId;
import java.util.Optional;

/**
 * Outbound port — implemented in {@code app}. Same shape/rationale as client-registry-module's own
 * identically-named port (a narrow, name-only cross-module read, not the full {@code Organization}
 * aggregate) — deliberately a SEPARATE interface, not a shared one: this module cannot depend on
 * client-registry-module, and the two differ in parameter types anyway ({@link OrganizationId}/
 * {@link PlatformAccountId} here, plain {@code UUID} there), same "module dependency rule forbids
 * the import, and the types genuinely differ" reasoning {@link CurrentPlatformAccountResolver}'s
 * own Javadoc already documents for an identical situation.
 */
@FunctionalInterface
public interface OrganizationForPlatformAccountResolver {

  @SuppressWarnings("PMD.LongVariable")
  Optional<String> resolveName(
      OrganizationId organizationId, PlatformAccountId ownerPlatformAccountId);
}
