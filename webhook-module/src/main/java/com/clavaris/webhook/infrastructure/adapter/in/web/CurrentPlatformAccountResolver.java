package com.clavaris.webhook.infrastructure.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — implemented in {@code app}, using real Spring Security machinery this module
 * deliberately does not depend on. Same "which {@code PlatformAccount} is making this dashboard
 * request" need organization-module's/client-registry-module's own identically-shaped ports already
 * solve for their own dashboard controllers — a separate interface here, not a shared import, same
 * module-independence rule those ports' own Javadoc already documents (each business module owns
 * its own copy; {@code app} is the one module allowed to bridge to the real Spring Security {@code
 * SecurityContext} all three need).
 *
 * <p>Empty, not thrown, for an unauthenticated request — {@link
 * PlatformWebhookEndpointController}'s own security filter chain (the same {@code
 * PlatformDashboardSecurityConfig} every other module's dashboard already relies on) already
 * guarantees this is never actually reached unauthenticated.
 */
@FunctionalInterface
public interface CurrentPlatformAccountResolver {

  Optional<UUID> resolve(HttpServletRequest request);
}
