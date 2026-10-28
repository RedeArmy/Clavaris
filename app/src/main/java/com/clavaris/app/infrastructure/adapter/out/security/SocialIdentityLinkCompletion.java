package com.clavaris.app.infrastructure.adapter.out.security;

import com.clavaris.identity.application.usecase.connectsocialidentity.ConnectSocialIdentityCommand;
import com.clavaris.identity.application.usecase.connectsocialidentity.ConnectSocialIdentityUseCase;
import com.clavaris.identity.application.usecase.connectsocialidentity.SocialConnectionNotAllowedException;
import com.clavaris.identity.application.usecase.connectsocialidentity.SocialIdentityAlreadyLinkedException;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.SocialProvider;
import com.clavaris.identity.infrastructure.adapter.in.web.AuthenticatedSessionEstablisher;
import com.clavaris.identity.infrastructure.adapter.in.web.SessionFactors;
import com.clavaris.identity.infrastructure.adapter.in.web.SocialLoginRedirectController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.stereotype.Component;

/**
 * TD-FUT-043: finishes a "connect a provider" round trip that a signed-in End-User started from the
 * Connected accounts page. {@code SocialLoginAuthenticationSuccessHandler} hands every provider
 * callback here first; if the session carries a link intent, the provider identity is attached to
 * <em>that</em> Account (never to whoever the provider says it is, and never signing anyone in as
 * the provider's account) and the handler's normal sign-in path is skipped.
 *
 * <p>The OAuth2 login filter has already replaced the browser's security context with the
 * provider's token by the time this runs, so the original Account's session is re-established here
 * — on success and on every failure alike, since the person was signed in when they asked. The
 * intent is single-use and expires after {@link #INTENT_TTL}: it is an authenticated action
 * remembered in the session, not a standing permission.
 */
@Component
class SocialIdentityLinkCompletion {

  private static final Logger LOG = LoggerFactory.getLogger(SocialIdentityLinkCompletion.class);
  private static final Duration INTENT_TTL = Duration.ofMinutes(10);
  private static final String CONNECTIONS_PATH = "/account/connections";

  private final ConnectSocialIdentityUseCase connect;
  private final AuthenticatedSessionEstablisher sessions;
  private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

  /* package */ SocialIdentityLinkCompletion(
      final ConnectSocialIdentityUseCase connect, final AuthenticatedSessionEstablisher sessions) {
    this.connect = connect;
    this.sessions = sessions;
  }

  /**
   * @return {@code true} if the callback was a link attempt and has been fully answered (a redirect
   *     was sent); {@code false} if there was no intent and the caller must carry on as a sign-in
   */
  /* package */ boolean completeIfRequested(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final HttpSession session,
      final String organizationIdValue,
      final SocialProvider provider,
      final String providerUserId)
      throws IOException {
    if (session == null) {
      return false;
    }
    final Object accountValue =
        session.getAttribute(SocialLoginRedirectController.LINK_ACCOUNT_ID_SESSION_ATTRIBUTE);
    final Object factors =
        session.getAttribute(SocialLoginRedirectController.LINK_FACTORS_SESSION_ATTRIBUTE);
    final Object requestedAt =
        session.getAttribute(SocialLoginRedirectController.LINK_REQUESTED_AT_SESSION_ATTRIBUTE);
    if (accountValue == null) {
      return false;
    }
    session.removeAttribute(SocialLoginRedirectController.LINK_ACCOUNT_ID_SESSION_ATTRIBUTE);
    session.removeAttribute(SocialLoginRedirectController.LINK_REQUESTED_AT_SESSION_ATTRIBUTE);
    session.removeAttribute(SocialLoginRedirectController.LINK_FACTORS_SESSION_ATTRIBUTE);
    if (organizationIdValue == null || expired(requestedAt)) {
      // An expired or half-formed intent proves nothing; fall back to the normal callback handling.
      return false;
    }

    final UUID accountId = UUID.fromString(accountValue.toString());
    final String base = "/o/" + organizationIdValue + CONNECTIONS_PATH;
    String outcome = "?connected";
    try {
      connect.handle(
          new ConnectSocialIdentityCommand(new AccountId(accountId), provider, providerUserId));
    } catch (final SocialIdentityAlreadyLinkedException _) {
      outcome = "?alreadyLinked";
    } catch (final SocialConnectionNotAllowedException _) {
      outcome = "?connectError";
    }
    LOG.info("event=social_link_callback accountId={} provider={}", accountId, provider);
    restoreOriginalSession(request, response, accountId, factors, provider, base);
    redirectStrategy.sendRedirect(request, response, base + outcome);
    return true;
  }

  // Restores the signed-in session the OAuth2 filter just overwrote, with the factors and
  // authentication time it had (TD-SEC-072), so a relying party does not read it as a new
  // sign-in. A session captured without factors (one planted before this existed) falls back to
  // the social sign-in it used to be. The returned target is ignored: this answer always goes
  // back to the Connected accounts page.
  private void restoreOriginalSession(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final UUID accountId,
      final Object factors,
      final SocialProvider provider,
      final String base) {
    final List<GrantedAuthority> original =
        SessionFactors.restore(factors instanceof List<?> specs ? specs : null);
    if (original.isEmpty()) {
      sessions.establishViaSocialLogin(request, response, accountId, provider, false, base);
    } else {
      sessions.restoreSession(request, response, accountId, original, base);
    }
  }

  // A String of epoch milliseconds, not an Instant: the sessions are allow-listed JSON
  // (TD-ARCH-039), which writes an Instant as a bare number and reads it back as something else, so
  // an Instant here silently made every connect look like a plain sign-in.
  private static boolean expired(final Object requestedAt) {
    if (!(requestedAt instanceof String millis)) {
      return true;
    }
    try {
      return Instant.ofEpochMilli(Long.parseLong(millis)).plus(INTENT_TTL).isBefore(Instant.now());
    } catch (final NumberFormatException _) {
      return true;
    }
  }
}
