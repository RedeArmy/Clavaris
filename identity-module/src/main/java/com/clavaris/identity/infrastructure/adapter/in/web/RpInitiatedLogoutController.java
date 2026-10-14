package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * OpenID Connect RP-Initiated Logout 1.0 §2: {@code id_token_hint} is only RECOMMENDED, so a logout
 * request without one must still work. Spring Authorization Server answers it with a 400, so the
 * app's {@code OidcLogoutWithoutHintFilter} takes such a request first and forwards here: the
 * End-User is asked to confirm (RP-Initiated Logout §4, an OP SHOULD ask before logging out on a
 * request it cannot attribute to the user), and the page shown afterwards when there is no
 * registered {@code post_logout_redirect_uri} to return to.
 *
 * <p>The confirmation carries its own single-use token, kept in the session, because Spring
 * Authorization Server's endpoints are exempt from the chain's CSRF protection: without it any site
 * could sign a visitor out by posting to the logout endpoint.
 *
 * <p>Reached only by a forward from that filter (which bypasses the security chain on purpose), so
 * it renders nothing but these two static pages and trusts nothing it is given beyond echoing the
 * request parameters back as hidden fields.
 */
// PMD.AtLeastOneConstructor: no state, only two stateless handlers. PMD.LongVariable: the attribute
// name says what it holds.
@SuppressWarnings({"PMD.AtLeastOneConstructor", "PMD.LongVariable"})
@Controller
@RequestMapping("/o/{organizationId}/logout")
public class RpInitiatedLogoutController {

  /** Session attribute holding the token the confirmation form must echo back. */
  public static final String CONFIRMATION_TOKEN_ATTRIBUTE = "clavaris.logout.confirmation-token";

  private static final SecureRandom RANDOM = new SecureRandom();

  // PMD.LongVariable: the post_logout_redirect_uri parameter name is the spec's own.
  @SuppressWarnings("PMD.LongVariable")
  @RequestMapping("/confirm")
  public String confirm(
      @PathVariable final UUID organizationId,
      @RequestParam(name = "client_id", required = false) final String clientId,
      @RequestParam(name = "post_logout_redirect_uri", required = false)
          final String postLogoutRedirectUri,
      @RequestParam(name = "state", required = false) final String state,
      final HttpServletRequest request,
      final Model model) {
    // Only an End-User who is signed in gets here (the filter completes any other request without a
    // page), so the session already exists; one that has gone in the meantime is not recreated just
    // to hold a token for nothing.
    final HttpSession session = request.getSession(false);
    String view = "identity/logout-confirm";
    if (session == null) {
      view = done(organizationId, model);
    } else {
      // A token already waiting in this session is kept, not replaced: a rejected POST shows this
      // page again, and minting a new token then would invalidate the one on the page the visitor
      // is looking at (another site could keep doing that to stop them signing out).
      final String token =
          session.getAttribute(CONFIRMATION_TOKEN_ATTRIBUTE) instanceof String existing
              ? existing
              : newToken();
      session.setAttribute(CONFIRMATION_TOKEN_ATTRIBUTE, token);
      model.addAttribute("organizationId", organizationId);
      model.addAttribute("confirmationToken", token);
      model.addAttribute("clientId", clientId);
      model.addAttribute("postLogoutRedirectUri", postLogoutRedirectUri);
      model.addAttribute("state", state);
    }
    return view;
  }

  private static String newToken() {
    final byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  @RequestMapping("/done")
  public String done(@PathVariable final UUID organizationId, final Model model) {
    model.addAttribute("organizationId", organizationId);
    return "identity/logged-out";
  }
}
