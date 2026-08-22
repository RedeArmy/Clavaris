package com.clavaris.app.infrastructure.config;

import com.clavaris.organization.infrastructure.adapter.in.web.CurrentPlatformAccountResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Implements organization-module's {@link CurrentPlatformAccountResolver} — reads the principal
 * name {@link SpringSecurityPlatformAuthenticatedSessionEstablisher} stored (the {@code
 * PlatformAccountId}'s own string form) straight off the current {@link SecurityContext}, the same
 * source {@link PlatformAccountSessionRevokerBridge} keys its own {@code SessionRegistry} lookup
 * by.
 */
@Component
class CurrentPlatformAccountResolverBridge implements CurrentPlatformAccountResolver {

  // This class holds no state, so this constructor is otherwise a no-op — written out explicitly
  // for the same reason as e.g. AdminApiSecurityConfig's own identical empty constructor.
  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ CurrentPlatformAccountResolverBridge() {
    // Intentionally empty.
  }

  // "Empty"/"malformed"/"resolved" are three independent, equally-valid outcomes here — each
  // needs its own exit, same rationale as e.g. RegisterAccountController's own suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Override
  public Optional<UUID> resolve(final HttpServletRequest request) {
    final SecurityContext context = SecurityContextHolder.getContext();
    final Authentication authentication = context.getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(authentication.getName()));
    } catch (final IllegalArgumentException notAUuid) {
      // A principal name that isn't a UUID at all can't be a PlatformAccountId — same
      // "malformed input surfaces as absent, never an exception" convention as
      // CurrentOrganizationContext's own empty-Optional paths.
      return Optional.empty();
    }
  }
}
