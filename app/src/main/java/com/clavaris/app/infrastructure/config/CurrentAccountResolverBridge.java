package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.infrastructure.adapter.in.web.CurrentAccountResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Implements identity-module's {@link CurrentAccountResolver} — tenant-tier mirror of {@code
 * CurrentPlatformAccountResolverBridge}; see that class's own Javadoc for the full rationale
 * (including why the authority check below is defense in depth, not the primary control). Reads the
 * principal name {@link SpringSecurityAuthenticatedSessionEstablisher} stored (the {@code
 * AccountId}'s own string form) straight off the current {@link SecurityContext}.
 */
@Component
class CurrentAccountResolverBridge implements CurrentAccountResolver {

  // The exact authority SpringSecurityAuthenticatedSessionEstablisher grants and no platform-tier
  // session ever carries.
  private static final String ACCOUNT_AUTHORITY = "ROLE_ACCOUNT";

  // This class holds no state, so this constructor is otherwise a no-op — written out explicitly
  // for the same reason as CurrentPlatformAccountResolverBridge's own identical constructor.
  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ CurrentAccountResolverBridge() {
    // Intentionally empty.
  }

  // "Empty"/"wrong tier"/"malformed"/"resolved" are four independent, equally-valid outcomes
  // here — same rationale as CurrentPlatformAccountResolverBridge's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Override
  public Optional<AccountId> resolve(final HttpServletRequest request) {
    final SecurityContext context = SecurityContextHolder.getContext();
    final Authentication authentication = context.getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      return Optional.empty();
    }
    final boolean isAccount =
        authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(ACCOUNT_AUTHORITY::equals);
    if (!isAccount) {
      return Optional.empty();
    }
    try {
      return Optional.of(new AccountId(UUID.fromString(authentication.getName())));
    } catch (final IllegalArgumentException _) {
      // A principal name that isn't a UUID at all can't be an AccountId — same "malformed input
      // surfaces as absent, never an exception" convention as CurrentPlatformAccountResolverBridge.
      return Optional.empty();
    }
  }
}
