package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.OrganizationSocialLoginPolicyProvider;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SocialProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The "Sign in with Google/GitHub" button's own target — both hosted login pages ({@link
 * LoginController}'s tenant form, {@link PlatformLoginController}'s platform form) link here, never
 * directly at Spring Security's own {@code /oauth2/authorization/{registrationId}} (the actual OIDC
 * dance's real starting point, wired by {@code app}'s {@code SocialLoginConfig}).
 *
 * <p>Exists purely to solve one problem: Google/GitHub redirect back to ONE shared callback URL
 * (ADR-0020 Decision 4 — a single Clavaris-owned OAuth app per provider, not one per tenant), so
 * nothing in that round trip's own state naturally carries "which Organization was this login for."
 * {@link #forOrganization} stashes {@code organizationId} in the {@link HttpSession} immediately
 * before handing off to Spring Security's redirect — the same browser session persists across the
 * whole three-hop dance (this codebase already relies on that same fact for {@code
 * HttpSessionRequestCache}), and {@code app}'s own success handler reads it back out once the
 * provider redirects here. {@link #forPlatform} deliberately does NOT set it — its absence at
 * callback time is itself the platform-tier signal.
 *
 * <p>ADR-0020 Decision 3, BR-ID-12: {@link #forOrganization} re-verifies {@code
 * OrganizationSocialLoginPolicyProvider} here too, before ever redirecting the browser to a
 * third-party provider — the same check {@code AuthenticateWithSocialProviderService} repeats again
 * on the way back (defense in depth, not redundancy: a UI-level button being shown is never trusted
 * as the actual authorization decision).
 */
// PMD.LongVariable: ORGANIZATION_ID_SESSION_ATTRIBUTE names exactly what it is — a shortened
// identifier would only make this constant harder to correlate with what it's for at the one
// other place it's read (SocialLoginAuthenticationSuccessHandler, app). PMD.LawOfDemeter:
// request.getSession() is the standard Servlet API shape for "the session this request now
// carries" — there is no other way to reach it, same rationale as AntiAbuseRateLimitingFilter's
// own response.getWriter() suppression. PMD.OnlyOneReturn: parseProvider's try/catch each need
// their own exit — same rationale as RegisterAccountController's own identical suppression.
@SuppressWarnings({"PMD.LongVariable", "PMD.LawOfDemeter", "PMD.OnlyOneReturn"})
@Controller
public class SocialLoginRedirectController {

  /**
   * Package-visible so {@code app}'s own success handler (which must run after both this
   * controller's redirect and Spring Security's own OAuth2 dance complete) reads back the exact
   * same key — same "define once, reference from the one place that reads it" convention as {@code
   * OrganizationAuthorizationServerConfig.LOGIN_PATH_PATTERN}.
   */
  public static final String ORGANIZATION_ID_SESSION_ATTRIBUTE =
      "clavaris.socialLogin.organizationId";

  /**
   * Clerk "customize redirect URLs" parity: same round-trip-through-the-session reasoning as {@link
   * #ORGANIZATION_ID_SESSION_ATTRIBUTE}'s own Javadoc — the external provider's callback carries
   * nothing of this codebase's own, so whatever {@code clientId}/{@code redirectUrl} this request
   * arrived with must be stashed here before the redirect and read back by {@code app}'s own
   * success handler. Both nullable — absent whenever the original request never carried them.
   */
  public static final String CLIENT_ID_SESSION_ATTRIBUTE = "clavaris.socialLogin.clientId";

  public static final String REDIRECT_URL_SESSION_ATTRIBUTE = "clavaris.socialLogin.redirectUrl";

  private final OrganizationSocialLoginPolicyProvider policyProvider;

  public SocialLoginRedirectController(final OrganizationSocialLoginPolicyProvider policyProvider) {
    this.policyProvider = policyProvider;
  }

  @GetMapping("/o/{organizationId}/login/social/{provider}")
  public void forOrganization(
      @PathVariable final UUID organizationId,
      @PathVariable final String provider,
      final HttpServletRequest request,
      final HttpServletResponse response,
      @RequestParam(required = false) final String clientId,
      @RequestParam(required = false) final String redirectUrl)
      throws IOException {
    final SocialProvider socialProvider = parseProvider(provider);
    if (socialProvider == null
        || !policyProvider.isProviderAllowed(new OrganizationId(organizationId), socialProvider)) {
      response.sendRedirect(
          request.getContextPath() + "/o/" + organizationId + "/login?socialLoginError");
      return;
    }

    // A plain String, not the UUID object itself — the simplest, least-doubtful value to round-trip
    // through a Redis-backed HttpSession (DistributedSessionConfig), no assumption about how the
    // configured session serializer handles an arbitrary class needed.
    final HttpSession session = request.getSession();
    session.setAttribute(ORGANIZATION_ID_SESSION_ATTRIBUTE, organizationId.toString());
    if (clientId != null) {
      session.setAttribute(CLIENT_ID_SESSION_ATTRIBUTE, clientId);
    }
    if (redirectUrl != null) {
      session.setAttribute(REDIRECT_URL_SESSION_ATTRIBUTE, redirectUrl);
    }
    response.sendRedirect(
        request.getContextPath()
            + "/oauth2/authorization/"
            + socialProvider.name().toLowerCase(Locale.ROOT));
  }

  @GetMapping("/platform/login/social/{provider}")
  public void forPlatform(
      @PathVariable final String provider,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws IOException {
    if (parseProvider(provider) == null) {
      response.sendRedirect(request.getContextPath() + "/platform/login?socialLoginError");
      return;
    }

    // Single-use, same discipline as every other side-channel state this codebase stashes in a
    // session (e.g. HttpSessionRequestCache) — a stale organizationId/clientId/redirectUrl left
    // over from an earlier, abandoned tenant social-login attempt on this same browser session
    // must never leak into a platform-tier one.
    final HttpSession existingSession = request.getSession(false);
    if (existingSession != null) {
      existingSession.removeAttribute(ORGANIZATION_ID_SESSION_ATTRIBUTE);
      existingSession.removeAttribute(CLIENT_ID_SESSION_ATTRIBUTE);
      existingSession.removeAttribute(REDIRECT_URL_SESSION_ATTRIBUTE);
    }
    response.sendRedirect(
        request.getContextPath() + "/oauth2/authorization/" + provider.toLowerCase(Locale.ROOT));
  }

  // Literal path segments take precedence over the {provider} variable segment above at the same
  // position — standard Spring MVC PathPattern matching, not a naming collision.
  @GetMapping("/o/{organizationId}/login/social/confirmation-required")
  public String tenantConfirmationRequired(@PathVariable final UUID organizationId) {
    return "identity/social-login-confirmation-required";
  }

  @GetMapping("/platform/login/social/confirmation-required")
  public String platformConfirmationRequired() {
    return "identity/platform/social-login-confirmation-required";
  }

  private SocialProvider parseProvider(final String provider) {
    try {
      return SocialProvider.valueOf(provider.toUpperCase(Locale.ROOT));
    } catch (final IllegalArgumentException _) {
      return null;
    }
  }
}
