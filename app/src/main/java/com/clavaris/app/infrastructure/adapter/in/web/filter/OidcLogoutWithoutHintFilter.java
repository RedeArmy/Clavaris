package com.clavaris.app.infrastructure.adapter.in.web.filter;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.identity.infrastructure.adapter.in.web.RpInitiatedLogoutController;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * OpenID Connect RP-Initiated Logout 1.0 §2 makes {@code id_token_hint} RECOMMENDED, not required,
 * but Spring Authorization Server answers a logout request without one with a 400. This filter
 * takes those requests before SAS's own logout filter, so a request that carries a hint is
 * untouched and behaves exactly as before.
 *
 * <ul>
 *   <li>GET, no hint, with an End-User signed in on this Organization: forwarded to the
 *       confirmation page ({@link RpInitiatedLogoutController}).
 *   <li>No hint and nobody signed in on this Organization: there is nothing to confirm or end, so
 *       the request is completed straight away (the redirect below, or the "signed out" page). No
 *       session is created for it, so a request without a session leaves no state behind.
 *   <li>POST, no hint, with the confirmation token that page put in the session: the End-User's
 *       session on this Organization is ended. The End-User is sent on to {@code
 *       post_logout_redirect_uri} (with {@code state}) only when {@code client_id} names an active
 *       client of this Organization and that URI is among its registered {@code
 *       post_logout_redirect_uris} (exact match, RP-Initiated Logout §3), otherwise to a plain
 *       "signed out" page, so the endpoint is never an open redirect.
 *   <li>POST without a valid token: the confirmation page again, and nothing is signed out. SAS's
 *       endpoints are exempt from the chain's CSRF protection, so this token is what stops another
 *       site from signing a visitor out.
 * </ul>
 */
public final class OidcLogoutWithoutHintFilter extends OncePerRequestFilter {

  private static final Pattern LOGOUT_PATH =
      Pattern.compile("^/o/([0-9a-fA-F-]{36})/connect/logout$");

  private final OAuthClientRepository oauthClients;
  private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();

  public OidcLogoutWithoutHintFilter(final OAuthClientRepository oauthClients) {
    super();
    this.oauthClients = oauthClients;
  }

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request, final HttpServletResponse response, final FilterChain chain)
      throws ServletException, IOException {
    final Matcher path =
        LOGOUT_PATH.matcher(UrlPathHelper.defaultInstance.getPathWithinApplication(request));
    final String hint = request.getParameter("id_token_hint");
    final boolean withoutHint = path.matches() && (hint == null || hint.isBlank());
    if (!withoutHint) {
      chain.doFilter(request, response);
      return;
    }
    final String organization = path.group(1);
    if (OidcRequestParameters.signedInAccount() == null) {
      completeLogout(request, response, organization);
    } else if ("POST".equals(request.getMethod()) && confirmationValid(request)) {
      logoutHandler.logout(
          request, response, SecurityContextHolder.getContext().getAuthentication());
      completeLogout(request, response, organization);
    } else {
      forwardAsGet(request, response, "/o/" + organization + "/logout/confirm");
    }
  }

  // Where the End-User goes once signed out: the registered post-logout URI, else the plain page.
  private void completeLogout(
      final HttpServletRequest request, final HttpServletResponse response, final String org)
      throws ServletException, IOException {
    final Optional<String> registeredUri =
        registeredPostLogoutUri(
            org,
            request.getParameter("client_id"),
            request.getParameter("post_logout_redirect_uri"));
    if (registeredUri.isPresent()) {
      final Map<String, String> parameters = new LinkedHashMap<>();
      parameters.put("state", request.getParameter("state"));
      response.sendRedirect(OidcRequestParameters.withQuery(registeredUri.get(), parameters));
    } else {
      forwardAsGet(request, response, "/o/" + org + "/logout/done");
    }
  }

  // The forward runs through the security chains again (it is filtered like any request), and the
  // chain that owns these pages protects POST with CSRF, which this internal hop must not trip: the
  // pages are plain GETs, so the forwarded request presents itself as one.
  private static void forwardAsGet(
      final HttpServletRequest request, final HttpServletResponse response, final String target)
      throws ServletException, IOException {
    final HttpServletRequest asGet =
        new HttpServletRequestWrapper(request) {
          @Override
          public String getMethod() {
            return "GET";
          }
        };
    request.getRequestDispatcher(target).forward(asGet, response);
  }

  // The token is single use: it is removed once it has been accepted. A wrong one is not allowed to
  // use it up, or another site posting with the visitor's cookie could make their own Sign out
  // click fail (the token itself is long enough that guessing it is not a way in).
  private static boolean confirmationValid(final HttpServletRequest request) {
    final HttpSession session = request.getSession(false);
    final String submitted = request.getParameter("confirmation_token");
    if (session == null || submitted == null) {
      return false;
    }
    final Object expected =
        session.getAttribute(RpInitiatedLogoutController.CONFIRMATION_TOKEN_ATTRIBUTE);
    final boolean valid =
        expected instanceof String token
            && MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), submitted.getBytes(StandardCharsets.UTF_8));
    if (valid) {
      session.removeAttribute(RpInitiatedLogoutController.CONFIRMATION_TOKEN_ATTRIBUTE);
    }
    return valid;
  }

  // The registered post-logout URI equal to the request's (exact match), taken from the list.
  private Optional<String> registeredPostLogoutUri(
      final String organizationId, final String clientId, final String redirectUri) {
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
        .flatMap(
            client ->
                OidcRequestParameters.registeredMatch(
                    client.postLogoutRedirectUris(), redirectUri));
  }
}
