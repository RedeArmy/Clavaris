package com.clavaris.app.infrastructure.config;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.ClientDomainConfigRepository;
import com.clavaris.clientregistry.domain.model.ClientDomainConfig;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * ADR-0009 §2/§4: the host→Organization resolution step a verified custom domain needs. Tenant
 * resolution everywhere else in this codebase is 100% path-based ({@code
 * OrganizationAuthorizationServerConfig}'s {@code /o/{organizationId}/...} convention, per its own
 * Javadoc) — a request arriving on {@code login.jobseeker.com/oauth2/authorize} has no such prefix
 * at all, so it matches none of the path-scoped {@code SecurityFilterChain}s (or falls through to
 * whichever one a bare {@code /oauth2/**} happens to match — the platform tier's own, the wrong one
 * entirely). This filter rewrites the request in place, prepending the resolved {@code
 * organizationId}, before Spring Security ever picks a chain for it.
 *
 * <p>Registered as a plain servlet {@link jakarta.servlet.Filter} via {@link FilterOrderingConfig},
 * not {@code httpSecurity.addFilterBefore(...)} inside any one {@code SecurityFilterChain} — chain
 * <em>selection</em> itself (via each chain's own {@code securityMatcher}) happens before any
 * filter added that way ever runs, so this has to sit in front of {@code FilterChainProxy} itself,
 * at the servlet-container level.
 *
 * <p>Uses {@code RequestDispatcher.forward}, not a redirect (ADR-0009 §4's own design) — the
 * browser's own address bar, and therefore the custom domain's cookie same-siteness ADR-0009 §1
 * exists for, never changes. A forward re-enters this same filter with the new, now-prefixed path;
 * the {@code /o/} guard below is what stops that second pass from forwarding again — the forward
 * target is always {@code /o/{organizationId}/...}, so that one prefix alone is a complete loop
 * guard (a bare {@code /oauth2/...} must NOT be excluded here: that is exactly the shape of the
 * very request this filter exists to rewrite on a real custom domain). Static assets ({@code
 * /js/**}) and operational endpoints ({@code /actuator/**}) are deliberately excluded — they are
 * genuinely origin-wide, not tenant-scoped, and the hosted login page's own {@code @{/js/...}}
 * references would 404 under an {@code /o/{organizationId}} prefix that no static-resource handler
 * is ever mounted at.
 */
final class CustomDomainRequestRewriteFilter extends OncePerRequestFilter {

  private static final String ORG_PREFIX = "/o/";
  private static final String STATIC_PREFIX = "/js/";
  private static final String ACTUATOR_PREFIX = "/actuator/";

  private final ClientDomainConfigRepository domainConfigs;
  private final OAuthClientRepository oauthClients;

  /* package */ CustomDomainRequestRewriteFilter(
      final ClientDomainConfigRepository domainConfigs, final OAuthClientRepository oauthClients) {
    super();
    this.domainConfigs = domainConfigs;
    this.oauthClients = oauthClients;
  }

  // "Excluded path"/"no matching verified domain"/"rewritten and forwarded" are three independent,
  // equally valid exits — same rationale as OrganizationCapacityRateLimitingFilter's own
  // suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Override
  protected void doFilterInternal(
      final HttpServletRequest request, final HttpServletResponse response, final FilterChain chain)
      throws ServletException, IOException {
    final String contextPath = request.getContextPath();
    final String pathWithinApp = request.getRequestURI().substring(contextPath.length());
    if (isExcludedFromRewrite(pathWithinApp)) {
      chain.doFilter(request, response);
      return;
    }
    final Optional<UUID> organizationId = resolveOrganizationIdForHost(request.getServerName());
    if (organizationId.isEmpty()) {
      chain.doFilter(request, response);
      return;
    }
    request
        .getRequestDispatcher(
            contextPath + ORG_PREFIX + organizationId.orElseThrow() + pathWithinApp)
        .forward(request, response);
  }

  private static boolean isExcludedFromRewrite(final String pathWithinApp) {
    return pathWithinApp.startsWith(ORG_PREFIX)
        || pathWithinApp.startsWith(STATIC_PREFIX)
        || pathWithinApp.startsWith(ACTUATOR_PREFIX);
  }

  // BR-CLIENT-04: only a VERIFIED domain is ever embedding/routing-eligible — a PENDING or FAILED
  // request must never silently route real traffic, same invariant ClientDomainConfig#isVerified
  // itself exists to make impossible to check incorrectly at any call site.
  private Optional<UUID> resolveOrganizationIdForHost(final String host) {
    return domainConfigs
        .findByHostname(host)
        .filter(ClientDomainConfig::isVerified)
        .flatMap(config -> oauthClients.findById(config.oauthClientId()))
        .map(OAuthClient::organizationId);
  }
}
