package com.clavaris.app.infrastructure.config;

import com.clavaris.organization.infrastructure.adapter.in.web.CurrentPlatformAccountResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Implements organization-module's {@link CurrentPlatformAccountResolver} — reads the principal
 * name {@link SpringSecurityPlatformAuthenticatedSessionEstablisher} stored (the {@code
 * PlatformAccountId}'s own string form) straight off the current {@link SecurityContext}, the same
 * source {@link PlatformAccountSessionRevokerBridge} keys its own {@code SessionRegistry} lookup
 * by.
 *
 * <p>TD-FUT-026 (closed 2026-09-02): identity-module's own same-shaped port, {@link
 * IdentityCurrentPlatformAccountResolverBridge}, is a SEPARATE class, not this one implementing a
 * second interface — {@code resolve(HttpServletRequest)} would then need two different return types
 * on the exact same erasure ({@code Optional<UUID>} here vs. {@code Optional<PlatformAccountId>}
 * there), which Java cannot express on one class. The auth-check logic itself is still duplicated
 * across the two, deliberately — the alternative (one shared private helper returning {@code
 * Optional<UUID>}, wrapped differently by each) was judged not worth the extra indirection for four
 * lines of logic.
 */
@Component
class CurrentPlatformAccountResolverBridge implements CurrentPlatformAccountResolver {

  // The exact authority SpringSecurityPlatformAuthenticatedSessionEstablisher grants and no
  // tenant-tier session ever carries — see this constant's use in resolve() below.
  @SuppressWarnings("PMD.LongVariable")
  private static final String PLATFORM_ACCOUNT_AUTHORITY = "ROLE_PLATFORM_ACCOUNT";

  // This class holds no state, so this constructor is otherwise a no-op — written out explicitly
  // for the same reason as e.g. AdminApiSecurityConfig's own identical empty constructor.
  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ CurrentPlatformAccountResolverBridge() {
    // Intentionally empty.
  }

  // "Empty"/"wrong tier"/"malformed"/"resolved" are four independent, equally-valid outcomes
  // here — each needs its own exit, same rationale as e.g. RegisterAccountController's own
  // suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Override
  public Optional<UUID> resolve(final HttpServletRequest request) {
    final SecurityContext context = SecurityContextHolder.getContext();
    final Authentication authentication = context.getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      return Optional.empty();
    }
    // Defense in depth, not the primary control (PlatformDashboardSecurityConfig's own
    // hasAuthority(PLATFORM_ACCOUNT_AUTHORITY) is): security finding (SDE-III review, 2026-08-22)
    // — this method used to trust any authenticated principal's name as a PlatformAccountId,
    // which is exactly what let a tenant Account's session (authenticated, but never carrying
    // this authority) resolve as if it were a real PlatformAccount. Checking it again here means
    // this class stays correct even if a future wiring mistake ever loosens the security-config
    // gate, the same "don't rely on a single layer" reasoning as e.g. CurrentOrganizationContext.
    final boolean isPlatformAccount =
        authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(PLATFORM_ACCOUNT_AUTHORITY::equals);
    if (!isPlatformAccount) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(authentication.getName()));
    } catch (final IllegalArgumentException _) {
      // A principal name that isn't a UUID at all can't be a PlatformAccountId — same
      // "malformed input surfaces as absent, never an exception" convention as
      // CurrentOrganizationContext's own empty-Optional paths.
      return Optional.empty();
    }
  }
}
