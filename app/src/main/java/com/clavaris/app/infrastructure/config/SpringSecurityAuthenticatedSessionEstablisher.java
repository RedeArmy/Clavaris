package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.domain.model.SocialProvider;
import com.clavaris.identity.infrastructure.adapter.in.web.AuthenticatedSessionEstablisher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Component;

/**
 * Adapts identity-module's {@link AuthenticatedSessionEstablisher} port to real Spring Security
 * machinery — the bridge lives in {@code app}, not identity-module, for the same reason {@code
 * OrganizationExistsCheckerBridge}'s implementation does (the module-graph's dependency rule):
 * identity-module deliberately never depends on {@code spring-security-config}.
 *
 * <p>Not going through the standard {@code UsernamePasswordAuthenticationFilter}-based form-login
 * DSL on purpose: that filter has no natural way to also carry a per-request {@code organizationId}
 * (BR-ORG-02's scoping), and {@code AuthenticateWithPasswordUseCase} already does the actual
 * credential verification — reusing {@code AuthenticationProvider}/{@code UserDetailsService} here
 * would mean re-deriving that same check a second time, or splitting it awkwardly across two
 * layers. Manually populating and persisting the {@link SecurityContext} here is the direct
 * equivalent of what {@code UsernamePasswordAuthenticationFilter.successfulAuthentication} does
 * internally — same mechanism, invoked from {@code LoginController} instead of a filter.
 *
 * <p>Because this bypasses the filter, it also bypasses Spring Security's default {@code
 * SessionAuthenticationStrategy} — the one piece of that mechanism this class cannot skip without
 * reopening a session-fixation hole (CWE-384): a session created before login (the {@code
 * RequestCache} step above requires one to exist) must not simply become the authenticated session
 * under the same ID, or an attacker who fixed that ID beforehand inherits it. {@link #establish}
 * rotates the session ID itself, the same defense {@code ChangeSessionIdAuthenticationStrategy}
 * applies in the standard filter chain.
 *
 * <p>ADR-0020: {@link #establishViaSocialLogin}, invoked from {@code
 * SocialLoginAuthenticationSuccessHandler} instead of {@code LoginController}, shares every one of
 * the concerns above — same CWE-384 fix, same manual {@link SecurityContext} population — with a
 * different {@code FactorGrantedAuthority}/AMR marker to reflect the real authentication mechanism.
 */
@Component
class SpringSecurityAuthenticatedSessionEstablisher implements AuthenticatedSessionEstablisher {

  private final SecurityContextRepository contextRepository;

  // Same instance shape SAS's own ExceptionTranslationFilter uses by default to remember "what was
  // the browser actually trying to reach" before redirecting to login — reusing the session-backed
  // implementation here, not a custom one, is what makes the two sides agree on where the
  // originally-requested /oauth2/authorize URL is stored.
  private final RequestCache requestCache = new HttpSessionRequestCache();

  /* package */ SpringSecurityAuthenticatedSessionEstablisher(
      final SecurityContextRepository contextRepository) {
    this.contextRepository = contextRepository;
  }

  @Override
  public String establish(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final UUID accountId,
      final String fallbackUrl) {
    // A FactorGrantedAuthority, not an empty authority list: SAS's own JwtGenerator computes the
    // OIDC auth_time claim by scanning the Authentication's authorities for one of these and
    // reading its issuedAt — confirmed live (a 500, "authenticationTime cannot be null", the first
    // time this method returned List.of()). PASSWORD_AUTHORITY names the mechanism actually used —
    // AuthenticateWithPasswordUseCase — Instant.now() is genuinely when it happened, not a filler
    // value.
    return establishWithAuthorities(
        request,
        response,
        accountId,
        List.of(
            FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY)
                .issuedAt(Instant.now())
                .build(),
            new SimpleGrantedAuthority("ROLE_ACCOUNT")),
        fallbackUrl);
  }

  @Override
  public String establishViaSocialLogin(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final UUID accountId,
      final SocialProvider provider,
      final String fallbackUrl) {
    // ADR-0020: FACTOR_AUTHORIZATION_CODE — the standard Spring Security authority for "an OAuth2
    // Authorization Code exchange authenticated this session," exactly what a social login via
    // Google/GitHub actually is under the hood, same auth_time role as PASSWORD_AUTHORITY above.
    // The AMR_-prefixed authority alongside it carries the specific provider — see
    // AuthenticationContextClaimsCustomizer's own Javadoc for how it turns this into a real OIDC
    // amr claim instead of always hardcoding ["pwd"].
    return establishWithAuthorities(
        request,
        response,
        accountId,
        List.of(
            FactorGrantedAuthority.withAuthority(
                    FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY)
                .issuedAt(Instant.now())
                .build(),
            new SimpleGrantedAuthority("ROLE_ACCOUNT"),
            new SimpleGrantedAuthority("AMR_" + provider.name())),
        fallbackUrl);
  }

  @Override
  public String establishViaOneTimeEmailProof(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final UUID accountId,
      final String fallbackUrl) {
    // ADR-0024 §3: FACTOR_OTT (Spring Security's own standard authority for a one-time-token-style
    // login, exactly what both the email code and email link mechanisms are structurally — a
    // single-use value proven once, never a stored, reusable credential the way a password is) for
    // auth_time, same role PASSWORD_AUTHORITY/AUTHORIZATION_CODE_AUTHORITY already play above. The
    // AMR_OTP authority is RFC 8176's own registered "otp" amr value — a real registry value, not a
    // minted one like the social-login provider names above (that registry has no per-provider
    // entries, but it does have one for exactly this factor).
    return establishWithAuthorities(
        request,
        response,
        accountId,
        List.of(
            FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.OTT_AUTHORITY)
                .issuedAt(Instant.now())
                .build(),
            new SimpleGrantedAuthority("ROLE_ACCOUNT"),
            new SimpleGrantedAuthority("AMR_OTP")),
        fallbackUrl);
  }

  private String establishWithAuthorities(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final UUID accountId,
      final List<GrantedAuthority> authorities,
      final String fallbackUrl) {
    // CWE-384 fix: rotate the session ID before the SecurityContext is attached to it, exactly as
    // ChangeSessionIdAuthenticationStrategy does for the standard filter-based login path. Only a
    // pre-existing session is at risk of fixation — request.getSession(false) never creates one, so
    // a request that arrives with no session yet (nothing to fix) is left alone; a fresh session
    // gets a fresh, attacker-unknowable ID for free when saveContext below creates it.
    // changeSessionId() (Servlet 3.1+), not invalidate()+getSession(true): it keeps the existing
    // session's attributes under the new ID, which matters here specifically because the
    // RequestCache above already stored the pre-login /oauth2/authorize request in this same
    // session — losing it would silently drop the user back to a generic page instead of resuming
    // the flow they started.
    final HttpSession existingSession = request.getSession(false);
    if (existingSession != null) {
      request.changeSessionId();
    }

    // ROLE_ACCOUNT (present in both callers' authority lists above): security finding (SDE-III
    // review, 2026-08-22) — before this, a tenant Account's authentication carried no tier marker
    // at all, only "how" it authenticated, which is exactly what let this same Authentication be
    // mistaken for a PlatformAccount's by anything checking authorities rather than
    // authority-agnostic authenticated(). ROLE_ACCOUNT is the tenant-tier mirror of
    // SpringSecurityPlatformAuthenticatedSessionEstablisher's own ROLE_PLATFORM_ACCOUNT — see
    // TenantAccountOnlySecurityContextFilter, the chain that actually checks it.
    final Authentication authentication =
        UsernamePasswordAuthenticationToken.authenticated(accountId.toString(), null, authorities);
    final SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    // Explicit save, not left to filter-chain-end persistence: SecurityContextHolderFilter (the
    // modern replacement for SecurityContextPersistenceFilter) only loads the context at the start
    // of a request — persisting a context set mid-request, from plain controller code rather than
    // an authentication filter, is this call's job alone.
    contextRepository.saveContext(context, request, response);

    // Self-service sessions/devices page: saveContext above guarantees a real HttpSession now
    // exists (creating one if request arrived with none) — this is the one place both tenant login
    // paths converge, so it's the only place that needs to populate these two attributes.
    // AccountActiveSessionsRepositoryBridge is the sole reader; see SessionDeviceAttributes' own
    // Javadoc for why plain Strings and why one shared constants holder.
    final HttpSession session = request.getSession(false);
    if (session != null) {
      session.setAttribute(SessionDeviceAttributes.USER_AGENT, request.getHeader("User-Agent"));
      session.setAttribute(SessionDeviceAttributes.SOURCE_IP, request.getRemoteAddr());
    }

    final SavedRequest savedRequest = requestCache.getRequest(request, response);
    return savedRequest != null ? savedRequest.getRedirectUrl() : fallbackUrl;
  }
}
