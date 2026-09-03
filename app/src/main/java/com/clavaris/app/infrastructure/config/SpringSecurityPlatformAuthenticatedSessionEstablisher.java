package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.infrastructure.adapter.in.web.PlatformAuthenticatedSessionEstablisher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Component;

/**
 * Adapts identity-module's {@link PlatformAuthenticatedSessionEstablisher} port to real Spring
 * Security machinery — mirrors {@link SpringSecurityAuthenticatedSessionEstablisher} exactly (same
 * CWE-384 session-fixation fix via {@code changeSessionId()}, same {@code RequestCache}-based
 * resume so an unauthenticated {@code /platform/dashboard} visit redirects back there after login),
 * minus the {@code FactorGrantedAuthority}/{@code auth_time} machinery — no {@code JwtGenerator}
 * ever reads this principal, so there is no OIDC claim to satisfy. Grants {@code
 * ROLE_PLATFORM_ACCOUNT} instead, the authority {@code PlatformDashboardSecurityConfig}'s own chain
 * checks.
 */
@Component
class SpringSecurityPlatformAuthenticatedSessionEstablisher
    implements PlatformAuthenticatedSessionEstablisher {

  private final SecurityContextRepository contextRepository;
  private final SessionRegistry sessionRegistry;
  private final RequestCache requestCache = new HttpSessionRequestCache();

  /* package */ SpringSecurityPlatformAuthenticatedSessionEstablisher(
      final SecurityContextRepository contextRepository, final SessionRegistry sessionRegistry) {
    this.contextRepository = contextRepository;
    this.sessionRegistry = sessionRegistry;
  }

  @Override
  public String establish(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final UUID platformAccountId,
      final String fallbackUrl) {
    final HttpSession existingSession = request.getSession(false);
    if (existingSession != null) {
      request.changeSessionId();
    }

    final Authentication authentication =
        UsernamePasswordAuthenticationToken.authenticated(
            platformAccountId.toString(),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_ACCOUNT")));
    final SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    contextRepository.saveContext(context, request, response);

    // Bypassing the standard filter chain also bypasses RegisterSessionAuthenticationStrategy,
    // which normally does this registration automatically — PlatformAccountSessionRevokerBridge's
    // own expireNow() call (ADR-0012's BR-ID-04 equivalent) has nothing to find without it.
    // TD-ARCH-002 (closed): under the SessionRegistry bean this project wires today
    // (SpringSessionBackedSessionRegistry), this call is a documented no-op — confirmed by reading
    // its own source, "we don't administer sessions ourselves." The real indexing happens
    // automatically, driven by Spring Session's own PrincipalNameIndexResolver reading the
    // authentication straight off the SPRING_SECURITY_CONTEXT attribute contextRepository.
    // saveContext (above) already wrote — nothing else needs to call it. Left in, not deleted:
    // harmless under the current registry, and still load-bearing if this project ever reverts to
    // a plain in-memory SessionRegistryImpl.
    // PMD.LawOfDemeter: request.getSession() is the standard Servlet API shape for "the session
    // this request now carries" — there is no other way to reach it.
    @SuppressWarnings("PMD.LawOfDemeter")
    final HttpSession currentSession = request.getSession();
    sessionRegistry.registerNewSession(currentSession.getId(), platformAccountId.toString());

    // TD-FUT-026 (closed 2026-09-02): self-service sessions/devices page for a PlatformAccount —
    // same two attributes, same SessionDeviceAttributes constants holder, same "populate once,
    // right after the session is established" placement as
    // SpringSecurityAuthenticatedSessionEstablisher's own identical write for the tenant tier.
    // PlatformAccountActiveSessionsRepositoryBridge is the sole reader.
    currentSession.setAttribute(
        SessionDeviceAttributes.USER_AGENT, request.getHeader("User-Agent"));
    currentSession.setAttribute(SessionDeviceAttributes.SOURCE_IP, request.getRemoteAddr());

    final SavedRequest savedRequest = requestCache.getRequest(request, response);
    return savedRequest != null ? savedRequest.getRedirectUrl() : fallbackUrl;
  }
}
