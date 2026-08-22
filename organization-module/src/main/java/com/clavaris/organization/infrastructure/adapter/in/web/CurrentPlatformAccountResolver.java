package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — implemented in {@code app}, using real Spring Security machinery (reading the
 * current {@code SecurityContext}) that organization-module deliberately does not depend on. Same
 * "identity-module never depends on spring-security-config" discipline applied here: this module
 * needs to know "which {@code PlatformAccount} is making this dashboard request" without importing
 * either Spring Security or identity-module's own {@code PlatformAccountId} type.
 *
 * <p>Empty, not thrown, for an unauthenticated request — {@link
 * PlatformOrganizationDashboardController}'s own security filter chain already guarantees this is
 * never actually reached unauthenticated; empty is the honest signature for "nothing resolved",
 * same convention as {@code CurrentOrganizationContext}'s own {@code Optional} return.
 */
@FunctionalInterface
public interface CurrentPlatformAccountResolver {

  Optional<UUID> resolve(HttpServletRequest request);
}
