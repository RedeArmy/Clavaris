package com.clavaris.app.infrastructure.adapter.in.web.filter;

import com.clavaris.app.infrastructure.adapter.out.security.OrganizationLoginRedirectEntryPoint;
import com.clavaris.app.infrastructure.adapter.out.security.RequestScopedConsentService;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

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
 *       means always): the End-User must authenticate again. The request is saved and the hosted
 *       login is shown; once the End-User has signed in again (a factor newer than the note this
 *       filter left in the session) the saved request resumes and passes through, so there is no
 *       redirect loop. The new login also produces the new {@code auth_time}.
 *   <li>{@code prompt=consent}: the consent page is shown even for scopes already approved. The
 *       stored consent is only ignored for this request ({@link RequestScopedConsentService}),
 *       never deleted, and the consent form's own submission carries no {@code prompt}, so it does
 *       not loop.
 * </ul>
 *
 * <p>Nothing here destroys the End-User's state on a request alone. Dropping the session or
 * forgetting a consent when the request arrives would let any page that can make the browser issue
 * it (an iframe of another site: the CSP only stops it being <em>shown</em>, not the request from
 * being made) log the visitor out or wipe their consents. The current authentication stays until a
 * new login replaces it, and a consent stays until a new approval replaces it; a request from
 * another site can at most cause a login page to be asked for. The note and the saved request it
 * writes are the only state, and they expire ({@link ReauthenticationMarker}).
 *
 * <p>{@code prompt=none} mixed with another value is {@code invalid_request}, and with a {@code
 * max_age} the session is older than it is {@code login_required}. Saving the request and leaving a
 * note only happen for a navigation, not for an image or script another site makes with the
 * End-User's cookie ({@link OidcRequestParameters#isNavigation}).
 *
 * <p>An error is only ever sent to a {@code redirect_uri} that is registered for the client of this
 * very Organization (exact match), never to a value taken from the request alone (open redirect,
 * RFC 6749 §10.15); otherwise the request is left to SAS, which shows its own error page.
 */
// PMD.OnlyOneReturn: several independent early exits — same rationale as the sibling filters in
// this package.
@SuppressWarnings("PMD.OnlyOneReturn")
public final class OidcAuthorizationRequestParamsFilter extends OncePerRequestFilter {

  private static final Pattern AUTHORIZE_PATH =
      Pattern.compile("^/o/([0-9a-fA-F-]{36})/oauth2/authorize$");
  // OIDC Core §3.1.2.6 error code; not among spring-security-oauth2-core's own OAuth2ErrorCodes.
  private static final String LOGIN_REQUIRED = "login_required";
  private static final String INVALID_REQUEST = "invalid_request";
  private static final int SINGLE_VALUE = 1;

  private final OAuthClientRepository oauthClients;
  private final Clock clock;
  private final RequestCache requestCache = new HttpSessionRequestCache();
  private final OrganizationLoginRedirectEntryPoint loginEntryPoint =
      new OrganizationLoginRedirectEntryPoint();

  public OidcAuthorizationRequestParamsFilter(final OAuthClientRepository oauthClients) {
    this(oauthClients, Clock.systemUTC());
  }

  /* package */ OidcAuthorizationRequestParamsFilter(
      final OAuthClientRepository oauthClients, final Clock clock) {
    super();
    this.oauthClients = oauthClients;
    this.clock = clock;
  }

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request, final HttpServletResponse response, final FilterChain chain)
      throws ServletException, IOException {
    final Matcher path =
        AUTHORIZE_PATH.matcher(UrlPathHelper.defaultInstance.getPathWithinApplication(request));
    if (!path.matches()) {
      chain.doFilter(request, response);
      return;
    }
    ReauthenticationMarker.discardExpired(request, clock.instant());
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
    final Authentication authentication = OidcRequestParameters.signedInAccount();
    final String maxAgeParam = request.getParameter("max_age");
    final Long maxAge = OidcRequestParameters.parseMaxAge(maxAgeParam);

    if (maxAgeParam != null && maxAge == null) {
      return answerWithError(client.isPresent(), INVALID_REQUEST, request, response);
    }
    if (prompts.contains("none")) {
      return answerPromptNone(
          prompts, client.isPresent(), authentication, maxAge, request, response);
    }
    return answerSignedInState(request, response, prompts, maxAge, client, authentication);
  }

  // The login-related parameters once a registered client is known: noting a fresh login that was
  // asked for, or re-authenticating / forgetting the consent for a signed-in End-User.
  private boolean answerSignedInState(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final List<String> prompts,
      final Long maxAge,
      final Optional<OAuthClient> client,
      final Authentication authentication)
      throws IOException {
    if (client.isEmpty()) {
      return false;
    }
    if (prompts.contains("consent")) {
      request.setAttribute(
          RequestScopedConsentService.IGNORE_STORED_CONSENT_ATTRIBUTE, Boolean.TRUE);
    }
    if (authentication == null) {
      ReauthenticationMarker.leaveIfFreshLoginWanted(request, prompts, maxAge, clock.instant());
      return false;
    }
    if (!OidcRequestParameters.isNavigation(request)
        || !reauthenticationRequired(request, authentication, prompts, maxAge)) {
      return false;
    }
    startReauthentication(request, response);
    return true;
  }

  // §3.1.2.1: none cannot be combined with another value; alone, it never shows a page, so a
  // missing or too old authentication is login_required.
  private boolean answerPromptNone(
      final List<String> prompts,
      final boolean registeredClient,
      final Authentication authentication,
      final Long maxAge,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws IOException {
    final boolean combined = prompts.size() != SINGLE_VALUE;
    final boolean loginNeeded =
        authentication == null || OidcRequestParameters.exceedsMaxAge(authentication, maxAge);
    final String error = combined ? INVALID_REQUEST : LOGIN_REQUIRED;
    return answerWithError(registeredClient && (combined || loginNeeded), error, request, response);
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
        prompts.contains("login") || OidcRequestParameters.exceedsMaxAge(authentication, maxAge);
    return wanted
        && !ReauthenticationMarker.consumedByLoginSince(
            request, OidcRequestParameters.latestFactorAt(authentication), clock.instant());
  }

  private void startReauthentication(
      final HttpServletRequest request, final HttpServletResponse response) throws IOException {
    requestCache.saveRequest(request, response);
    ReauthenticationMarker.leave(request, clock.instant());
    loginEntryPoint.commence(
        request, response, new InsufficientAuthenticationException("re-authentication requested"));
  }

  private void redirectWithError(
      final HttpServletRequest request, final HttpServletResponse response, final String error)
      throws IOException {
    final Map<String, String> parameters = new LinkedHashMap<>();
    parameters.put("error", error);
    parameters.put("state", request.getParameter("state"));
    response.sendRedirect(
        OidcRequestParameters.withQuery(request.getParameter("redirect_uri"), parameters));
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
}
