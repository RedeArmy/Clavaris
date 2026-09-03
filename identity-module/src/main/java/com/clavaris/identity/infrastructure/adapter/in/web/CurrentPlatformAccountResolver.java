package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.domain.model.PlatformAccountId;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * Outbound port — implemented in {@code app}. TD-FUT-026 (closed 2026-09-02): identity-module's own
 * mirror of {@link CurrentAccountResolver}, resolving {@link PlatformAccountId} instead of {@link
 * com.clavaris.identity.domain.model.AccountId} — deliberately a SEPARATE interface from
 * organization-module's own same-shaped {@code CurrentPlatformAccountResolver} (which returns a raw
 * {@code UUID}, not a {@link PlatformAccountId}, and lives in a different module identity-module
 * cannot depend on), not a shared one: the module dependency rule (§7.2) forbids identity-module
 * importing anything from organization-module. {@code app}'s own bridge implements both interfaces
 * in one class — see that bridge's own Javadoc.
 */
@FunctionalInterface
public interface CurrentPlatformAccountResolver {

  Optional<PlatformAccountId> resolve(HttpServletRequest request);
}
