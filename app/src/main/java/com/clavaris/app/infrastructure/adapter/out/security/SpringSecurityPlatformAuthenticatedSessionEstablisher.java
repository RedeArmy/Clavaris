package com.clavaris.app.infrastructure.adapter.out.security;

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
public class SpringSecurityPlatformAuthenticatedSessionEstablisher
    implements PlatformAuthenticatedSessionEstablisher {

  private final SecurityContextRepository contextRepository;
  private final SessionRegistry sessionRegistry;
  private final RequestCache requestCache = new HttpSessionRequestCache();

  public SpringSecurityPlatformAuthenticatedSessionEstablisher(
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
