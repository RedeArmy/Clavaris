package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — implemented in {@code app}, using real Spring Security machinery
 * client-registry-module deliberately does not depend on. Same "which {@code PlatformAccount} is
 * making this dashboard request" need organization-module's own identically-shaped port already
 * solves for its own dashboard controllers — a separate interface here, not a shared import, same
 * module-independence rule that port's own Javadoc documents (each business module owns its own
 * copy; {@code app} is the one module allowed to bridge to the real Spring Security {@code
 * SecurityContext} both need).
 *
 * <p>Empty, not thrown, for an unauthenticated request — {@link
 * PlatformOrganizationClientController}'s own security filter chain (the same {@code
 * PlatformDashboardSecurityConfig} organization-module's dashboard already relies on, since every
 * {@code /platform/dashboard/**} route is covered by one rule) already guarantees this is never
 * actually reached unauthenticated.
 */
@FunctionalInterface
public interface CurrentPlatformAccountResolver {

  Optional<UUID> resolve(HttpServletRequest request);
}
