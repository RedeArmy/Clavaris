package com.clavaris.app.infrastructure.adapter.in.web.filter;

import com.clavaris.app.infrastructure.adapter.out.security.OrganizationLoginRedirectEntryPoint;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * OpenID Connect Core 1.0 §3.1.2.1 / §3.1.2.6: the {@code prompt} and {@code max_age} parameters of
 * the authorization request, which Spring Authorization Server does not act on.
 *
 * <ul>
 *   <li>{@code prompt=none}: never show a login or consent page. With no signed-in End-User the
 *       request is answered with {@code error=login_required} (and {@code state}) at the {@code
 *       redirect_uri}; SAS itself already rejects {@code none} mixed with other values and answers
 *       {@code consent_required} when a consent would be needed.
 *   <li>{@code prompt=login}, or a {@code max_age} the current authentication is older than (0
 *       means always): the End-User must authenticate again. The current authentication is dropped,
 *       the request is saved and the hosted login is shown; once the End-User has signed in again
 *       (a factor newer than the marker this filter left in the session) the saved request resumes
 *       and passes through, so there is no redirect loop. The new login also produces the new
 *       {@code auth_time}.
 *   <li>{@code prompt=consent}: the stored consent for this client and End-User is removed, so the
 *       consent page is shown even for scopes already approved. The consent form's own submission
 *       carries no {@code prompt}, so it does not loop.
 * </ul>
 *
 * <p>An error is only ever sent to a {@code redirect_uri} that is registered for the client of this
 * very Organization (exact match), never to a value taken from the request alone (open redirect,
 * RFC 6749 §10.15); otherwise the request is left to SAS, which shows its own error page.
 */
// PMD.LongVariable/OnlyOneReturn: several independent early exits and descriptive names — same
// rationale as the sibling filters in this package.
@SuppressWarnings({"PMD.LongVariable", "PMD.OnlyOneReturn", "PMD.LawOfDemeter"})
public final class OidcAuthorizationRequestParamsFilter extends OncePerRequestFilter {

  private static final String REAUTH_REQUESTED_AT = "clavaris.oidc.reauth.requested-at";

  private static final Pattern AUTHORIZE_PATH =
      Pattern.compile("^/o/([0-9a-fA-F-]{36})/oauth2/authorize$");
  private static final String ROLE_ACCOUNT = "ROLE_ACCOUNT";
  // OIDC Core §3.1.2.6 error code; not among spring-security-oauth2-core's own OAuth2ErrorCodes.
  private static final String LOGIN_REQUIRED = "login_required";
  private static final String INVALID_REQUEST = "invalid_request";

  private final OAuthClientRepository oauthClients;
  private final OAuth2AuthorizationConsentService consents;
  private final SecurityContextRepository contextRepository;
  private final RequestCache requestCache = new HttpSessionRequestCache();
  private final OrganizationLoginRedirectEntryPoint loginEntryPoint =
      new OrganizationLoginRedirectEntryPoint();

  public OidcAuthorizationRequestParamsFilter(
      final OAuthClientRepository oauthClients,
      final OAuth2AuthorizationConsentService consents,
      final SecurityContextRepository contextRepository) {
    super();
    this.oauthClients = oauthClients;
    this.consents = consents;
    this.contextRepository = contextRepository;
  }

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request, final HttpServletResponse response, final FilterChain chain)
      throws ServletException, IOException {
    final Matcher path = AUTHORIZE_PATH.matcher(request.getRequestURI());
    if (!path.matches()) {
      chain.doFilter(request, response);
      return;
    }
    final String unsupported = OidcRequestParameters.unsupportedRequestError(request);
    final boolean relevant =
        unsupported != null
            || request.getParameter("prompt") != null
            || request.getParameter("max_age") != null;
    if (unsupported != null && registeredClient(path.group(1), request).isPresent()) {
      redirectWithError(request, response, unsupported);
    } else if (!relevant || !answered(request, response, path.group(1))) {
      chain.doFilter(request, response);
    }
  }

  // True when this filter already wrote the response (an error redirect or the login redirect).
  private boolean answered(
      final HttpServletRequest request, final HttpServletResponse response, final String org)
      throws IOException {
    final List<String> prompts = OidcRequestParameters.promptValues(request.getParameter("prompt"));
    final Optional<OAuthClient> client = registeredClient(org, request);
    final Authentication authentication = signedInAccount();
    final String maxAgeParam = request.getParameter("max_age");
    final Long maxAge = OidcRequestParameters.parseMaxAge(maxAgeParam);

    if (prompts.size() == 1 && prompts.contains("none")) {
      return answerWithError(
          authentication == null && client.isPresent(), LOGIN_REQUIRED, request, response);
    }
    if (maxAgeParam != null && maxAge == null) {
      return answerWithError(client.isPresent(), INVALID_REQUEST, request, response);
    }
    if (authentication == null || client.isEmpty()) {
      return false;
    }
    if (reauthenticationRequired(request, authentication, prompts, maxAge)) {
      startReauthentication(request, response);
      return true;
    }
    if (prompts.contains("consent")) {
      forgetConsent(client.get(), authentication);
    }
    return false;
  }

  private void forgetConsent(final OAuthClient client, final Authentication authentication) {
    final OAuth2AuthorizationConsent stored =
        consents.findById(client.id().toString(), authentication.getName());
    if (stored != null) {
      consents.remove(stored);
    }
  }

  private boolean answerWithError(
      final boolean applicable,
      final String error,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws IOException {
    if (applicable) {
      redirectWithError(request, response, error);
    }
    return applicable;
  }

  private boolean reauthenticationRequired(
      final HttpServletRequest request,
      final Authentication authentication,
      final List<String> prompts,
      final Long maxAge) {
    final boolean wanted =
        prompts.contains("login")
            || (maxAge != null
                && (maxAge == 0
                    || Duration.between(
                                OidcRequestParameters.latestFactorAt(authentication), Instant.now())
                            .compareTo(Duration.ofSeconds(maxAge))
                        > 0));
    if (!wanted) {
      return false;
    }
    final HttpSession session = request.getSession(false);
    final Object marker = session == null ? null : session.getAttribute(REAUTH_REQUESTED_AT);
    if (marker instanceof Instant requestedAt
        && OidcRequestParameters.latestFactorAt(authentication).isAfter(requestedAt)) {
      session.removeAttribute(REAUTH_REQUESTED_AT);
      return false;
    }
    return true;
  }

  private void startReauthentication(
      final HttpServletRequest request, final HttpServletResponse response) throws IOException {
    requestCache.saveRequest(request, response);
    request.getSession().setAttribute(REAUTH_REQUESTED_AT, Instant.now());
    SecurityContextHolder.clearContext();
    contextRepository.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
    loginEntryPoint.commence(
        request, response, new InsufficientAuthenticationException("re-authentication requested"));
  }

  private void redirectWithError(
      final HttpServletRequest request, final HttpServletResponse response, final String error)
      throws IOException {
    final UriComponentsBuilder target =
        UriComponentsBuilder.fromUriString(request.getParameter("redirect_uri"))
            .queryParam("error", error);
    final String state = request.getParameter("state");
    if (state != null) {
      target.queryParam("state", state);
    }
    response.sendRedirect(target.build().encode().toUriString());
  }

  // Only an exact match of a redirect_uri registered for this Organization's active client makes
  // an error redirect safe — see this class's own Javadoc.
  private Optional<OAuthClient> registeredClient(
      final String organizationId, final HttpServletRequest request) {
    final String clientId = request.getParameter("client_id");
    final String redirectUri = request.getParameter("redirect_uri");
    if (clientId == null || redirectUri == null) {
      return Optional.empty();
    }
    final UUID organization;
    try {
      organization = UUID.fromString(organizationId);
    } catch (final IllegalArgumentException _) {
      return Optional.empty();
    }
    return oauthClients
        .findByClientId(clientId)
        .filter(OAuthClient::active)
        .filter(client -> client.organizationId().equals(organization))
        .filter(client -> client.redirectUris().contains(redirectUri));
  }

  private static Authentication signedInAccount() {
    final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      return null;
    }
    final boolean isAccount =
        authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(ROLE_ACCOUNT::equals);
    return isAccount ? authentication : null;
  }
}
