package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionPolicyProvider;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Clerk "Sessions" settings parity — the reverification-window check: whether the current request's
 * own authentication is recent enough to perform a sensitive self-service action without asking the
 * Account to re-prove who they are first. Reusable by any self-service controller that wants to opt
 * in, not retrofitted onto every existing one in this same pass — {@code
 * AccountWebAuthnCredentialsController}'s passkey-deletion endpoint is the first real caller.
 *
 * <p>Deliberately reads {@link FactorGrantedAuthority#getIssuedAt()} off the current {@link
 * Authentication} rather than stamping a new, separate HttpSession attribute at login time: {@code
 * SpringSecurityAuthenticatedSessionEstablisher} (app) already adds exactly one {@code
 * FactorGrantedAuthority} with a real {@code issuedAt} to every tenant Account session it
 * establishes — the same value SAS's own {@code JwtGenerator} already reads to compute the OIDC
 * {@code auth_time} claim — so this is the existing "how recently did this session actually
 * authenticate" signal, not a second, independently-maintained copy of it that could drift.
 *
 * <p>Fails closed: no {@link Authentication}, or one carrying no {@link FactorGrantedAuthority} at
 * all, is treated as stale (reverification required) — a security check that cannot prove a recent
 * authentication must never assume one, same posture {@code WebhookUrlSsrfChecker}'s own
 * unresolvable-host handling already establishes for an unrelated check.
 */
@Component
public class RequireRecentAuthentication {

  private final SessionPolicyProvider sessionPolicyProvider;

  public RequireRecentAuthentication(final SessionPolicyProvider sessionPolicyProvider) {
    this.sessionPolicyProvider = sessionPolicyProvider;
  }

  public boolean isStale(final UUID organizationId) {
    final Instant authenticatedAt =
        mostRecentFactorIssuedAt(SecurityContextHolder.getContext().getAuthentication());
    if (authenticatedAt == null) {
      return true;
    }
    final int reverificationWindowMinutes =
        sessionPolicyProvider
            .policyFor(new OrganizationId(organizationId))
            .reverificationWindowMinutes();
    return Instant.now()
        .isAfter(authenticatedAt.plus(Duration.ofMinutes(reverificationWindowMinutes)));
  }

  private static Instant mostRecentFactorIssuedAt(final Authentication authentication) {
    if (authentication == null) {
      return null;
    }
    Instant latest = null;
    for (final GrantedAuthority authority : authentication.getAuthorities()) {
      if (authority instanceof FactorGrantedAuthority factor && factor.getIssuedAt() != null) {
        final Instant issuedAt = factor.getIssuedAt();
        if (latest == null || issuedAt.isAfter(latest)) {
          latest = issuedAt;
        }
      }
    }
    return latest;
  }
}
