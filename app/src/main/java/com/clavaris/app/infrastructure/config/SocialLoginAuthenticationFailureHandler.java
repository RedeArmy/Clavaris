package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.infrastructure.adapter.in.web.SocialLoginRedirectController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * ADR-0020: covers every way the OAuth2 dance itself can fail before {@link
 * SocialLoginAuthenticationSuccessHandler} ever runs — the user declining consent on
 * Google/GitHub's own screen, an invalid/expired {@code state}, {@link
 * GitHubVerifiedEmailUserService}'s own {@code OAuth2AuthenticationException} when GitHub's {@code
 * /user/emails} call itself fails, or any other {@code OAuth2AuthenticationException} Spring
 * Security's own filter raises. Never logs or otherwise exposes the exception's own message to the
 * browser (same BR-DATA-01/TD-SEC-015 discipline {@code GlobalExceptionHandler} already applies) —
 * a generic {@code socialLoginError} query flag is all the redirected-to login page needs to show
 * its existing, already-generic error copy.
 */
// PMD.LongVariable: organizationIdValue names exactly what it is, same convention
// SocialLoginAuthenticationSuccessHandler's own identical suppression already documents.
@SuppressWarnings("PMD.LongVariable")
@Component
class SocialLoginAuthenticationFailureHandler implements AuthenticationFailureHandler {

  private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

  // Written out explicitly for the same reason as Argon2PasswordHasher's own constructor — only
  // Spring's own component scan ever needs to instantiate this class.
  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ SocialLoginAuthenticationFailureHandler() {
    // Intentionally empty — this class holds no state beyond the field above.
  }

  @Override
  public void onAuthenticationFailure(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final AuthenticationException exception)
      throws IOException {
    // Same single-use discipline as SocialLoginAuthenticationSuccessHandler — a failed attempt must
    // not leave a stale organizationId behind for whatever the browser tries next on this session.
    final HttpSession session = request.getSession(false);
    final String organizationIdValue =
        session == null
            ? null
            : (String)
                session.getAttribute(
                    SocialLoginRedirectController.ORGANIZATION_ID_SESSION_ATTRIBUTE);
    if (session != null) {
      session.removeAttribute(SocialLoginRedirectController.ORGANIZATION_ID_SESSION_ATTRIBUTE);
    }

    final String target =
        organizationIdValue == null
            ? "/platform/login?socialLoginError"
            : "/o/" + organizationIdValue + "/login?socialLoginError";
    redirectStrategy.sendRedirect(request, response, target);
  }
}
