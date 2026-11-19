package com.clavaris.app.infrastructure.adapter.in.web.filter;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcLogoutAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.oidc.web.authentication.OidcLogoutAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UrlPathHelper;

/**
 * What the End-User sees after a logout that carries an {@code id_token_hint} but no {@code
 * post_logout_redirect_uri} (OpenID Connect RP-Initiated Logout 1.0 §2, where the URI is optional).
 *
 * <p>Spring Authorization Server's own handler ends the session and then redirects to {@code /},
 * the root of the host: for an Organization's End-User that is not a page about having signed out
 * and not one of the Organization's own. This handler ends the session the same way and then shows
 * the Organization's "signed out" page, the one a logout without a hint already ends on ({@link
 * OidcLogoutWithoutHintFilter}).
 *
 * <p>Every other request is handed to SAS's handler untouched, so a logout that names a registered
 * {@code post_logout_redirect_uri} still redirects there with its {@code state}, and nothing about
 * how that URI is validated changes: SAS has already done that by the time this runs.
 */
public final class LogoutDoneAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

  private static final Pattern LOGOUT_PATH =
      Pattern.compile("^/o/([0-9a-fA-F-]{36})/connect/logout$");

  private final AuthenticationSuccessHandler redirecting =
      new OidcLogoutAuthenticationSuccessHandler();
  private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();

  @Override
  public void onAuthenticationSuccess(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final Authentication authentication)
      throws IOException, ServletException {
    final Matcher path =
        LOGOUT_PATH.matcher(UrlPathHelper.defaultInstance.getPathWithinApplication(request));
    if (authentication instanceof OidcLogoutAuthenticationToken token
        && !StringUtils.hasText(token.getPostLogoutRedirectUri())
        && path.matches()) {
      if (token.isPrincipalAuthenticated()) {
        logoutHandler.logout(request, response, (Authentication) token.getPrincipal());
      }
      OidcLogoutWithoutHintFilter.forwardAsGet(
          request, response, "/o/" + path.group(1) + "/logout/done");
      return;
    }
    redirecting.onAuthenticationSuccess(request, response, authentication);
  }
}
