package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.infrastructure.adapter.in.web.CurrentPlatformAccountResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Implements identity-module's {@link CurrentPlatformAccountResolver} — TD-FUT-026 (closed
 * 2026-09-02). A separate class from {@link CurrentPlatformAccountResolverBridge}
 * (organization-module's own same-shaped port), not the same class implementing both, purely
 * because Java cannot express two {@code resolve(HttpServletRequest)} overloads differing only in
 * return type ({@code Optional<UUID>} vs. {@code Optional<PlatformAccountId>}) on one class — see
 * that class's own Javadoc. Same resolution logic as that class, deliberately duplicated rather
 * than factored out for four lines.
 */
@Component
class IdentityCurrentPlatformAccountResolverBridge implements CurrentPlatformAccountResolver {

  @SuppressWarnings("PMD.LongVariable")
  private static final String PLATFORM_ACCOUNT_AUTHORITY = "ROLE_PLATFORM_ACCOUNT";

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ IdentityCurrentPlatformAccountResolverBridge() {
    // Intentionally empty.
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @Override
  public Optional<PlatformAccountId> resolve(final HttpServletRequest request) {
    final SecurityContext context = SecurityContextHolder.getContext();
    final Authentication authentication = context.getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      return Optional.empty();
    }
    final boolean isPlatformAccount =
        authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(PLATFORM_ACCOUNT_AUTHORITY::equals);
    if (!isPlatformAccount) {
      return Optional.empty();
    }
    try {
      return Optional.of(new PlatformAccountId(UUID.fromString(authentication.getName())));
    } catch (final IllegalArgumentException _) {
      return Optional.empty();
    }
  }
}
