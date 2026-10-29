package com.clavaris.app.infrastructure.adapter.out.security;

import com.clavaris.identity.application.usecase.authenticateplatformaccountwithsocialprovider.AuthenticatePlatformAccountWithSocialProviderCommand;
import com.clavaris.identity.application.usecase.authenticateplatformaccountwithsocialprovider.AuthenticatePlatformAccountWithSocialProviderResult;
import com.clavaris.identity.application.usecase.authenticateplatformaccountwithsocialprovider.AuthenticatePlatformAccountWithSocialProviderUseCase;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.AuthenticateWithSocialProviderCommand;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.AuthenticateWithSocialProviderResult;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.AuthenticateWithSocialProviderUseCase;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.EmailSubaddressNotAllowedException;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.SocialLoginNotAllowedException;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.UnverifiedProviderEmailException;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.KnownDeviceRepository;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceCommand;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceUseCase;
import com.clavaris.identity.application.usecase.recordloginevent.RecordLoginEventCommand;
import com.clavaris.identity.application.usecase.recordloginevent.RecordLoginEventUseCase;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestdevicetrustchallenge.RequestDeviceTrustChallengeUseCase;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectAction;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectUrlResolver;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.domain.model.SocialProvider;
import com.clavaris.identity.infrastructure.adapter.in.web.AuthenticatedSessionEstablisher;
import com.clavaris.identity.infrastructure.adapter.in.web.DeviceCookie;
import com.clavaris.identity.infrastructure.adapter.in.web.DeviceTrustGate;
import com.clavaris.identity.infrastructure.adapter.in.web.PendingAuthenticationFactor;
import com.clavaris.identity.infrastructure.adapter.in.web.PlatformAuthenticatedSessionEstablisher;
import com.clavaris.identity.infrastructure.adapter.in.web.SessionTaskGate;
import com.clavaris.identity.infrastructure.adapter.in.web.SocialLoginRedirectController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * ADR-0020: the single place both tiers' OAuth2 login dance converges once Google/GitHub redirect
 * back and Spring Security's own {@code OAuth2LoginAuthenticationFilter} has already exchanged the
 * code and fetched the user's profile (via the default {@code OidcUserService} for Google, {@link
 * GitHubVerifiedEmailUserService} for GitHub). Everything below runs entirely against this
 * codebase's own {@code AuthenticateWithSocialProviderUseCase}/{@code
 * AuthenticatePlatformAccountWithSocialProviderUseCase} — Spring Security's own {@link
 * OAuth2AuthenticationToken} that authenticated {@code authentication} is read once for the
 * provider/attributes it carries and then discarded; it is never what ends up as this session's
 * real principal (see {@link AuthenticatedSessionEstablisher}/{@link
 * PlatformAuthenticatedSessionEstablisher} for why — same "reuse the use case's own decision, don't
 * re-derive it from a framework type" split as {@code LoginController} already establishes for
 * password login).
 *
 * <p>Tenant vs. platform is resolved by presence, not a request parameter: {@link
 * SocialLoginRedirectController#ORGANIZATION_ID_SESSION_ATTRIBUTE} is only ever set by that
 * controller's own tenant-scoped entry point (see its Javadoc for the full "why a session
 * attribute" reasoning) — read here exactly once, then removed regardless of outcome (single-use,
 * same discipline as every other side-channel state this codebase stashes in a session).
 */
// PMD.LongVariable: organizationIdValue names exactly what it is, reused across three methods —
// see SocialLoginAuthenticationFailureHandler's own identical suppression. PMD.LawOfDemeter:
// principal.getName() is the standard OAuth2User API shape — there is no other way to reach it,
// same rationale AntiAbuseRateLimitingFilter's own response.getWriter() suppression already
// documents. PMD.OnlyOneReturn: every method flagged here has two real, distinct outcomes (an
// early-exit error path vs. the real result) — same "each outcome needs its own exit" rationale
// as SetRateLimitPolicyController's own identical suppression. The formerly-repeated "/o/" literal
// is now TENANT_PATH_PREFIX below, so no PMD.AvoidDuplicateLiterals suppression is needed either.
// java:S1075: every redirect target in this class ("/platform/dashboard" and its siblings below)
// is a route this server-rendered app owns and serves itself, not an external URI a deployment
// should be able to repoint — same "these are code, not runtime config" reasoning as every other
// hardcoded path already in this class (TENANT_PATH_PREFIX, the confirmation-required/error
// redirects), not an oversight specific to this one literal. PMD.ExcessiveImports: this class
// genuinely orchestrates both tiers' own use case/command/result/session-establisher types plus
// the record-pattern accountId/platformAccountId types the code review's own record-pattern fix
// added — same "wiring together many distinct types is the job" reasoning
// RefreshTokenRotationAuthenticationProvider's own identical suppression already documents.
// PMD.CouplingBetweenObjects: TD-SEC-055's own four new collaborators (accounts, knownDevices,
// requestDeviceTrustChallenge, authenticationPolicyProvider) pushed this class's own count past
// the threshold — same "wiring, not sprawl" reasoning as PMD.ExcessiveImports above, on the same
// class, for the same reason.
@SuppressWarnings({"java:S1075", "PMD.ExcessiveImports", "PMD.CouplingBetweenObjects"})
@Component
public class SocialLoginAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

  private static final String TENANT_PATH_PREFIX = "/o/";

  private final AuthenticateWithSocialProviderUseCase tenantUseCase;
  private final AuthenticatePlatformAccountWithSocialProviderUseCase platformUseCase;
  private final AuthenticatedSessionEstablisher tenantSessions;
  private final PlatformAuthenticatedSessionEstablisher platformSessions;
  private final RecordAccountLoginDeviceUseCase recordLoginDevice;
  private final RedirectUrlResolver redirectUrlResolver;
  // TD-SEC-055: the same four collaborators the four password/email-code/email-link controllers
  // already thread through DeviceTrustGate/SessionTaskGate — this handler never had them before,
  // which is exactly why social login could establish a session without either check running.
  private final AccountRepository accounts;
  private final KnownDeviceRepository knownDevices;
  private final RequestDeviceTrustChallengeUseCase requestDeviceTrustChallenge;
  private final AccountAuthenticationPolicyProvider authenticationPolicyProvider;
  private final RecordLoginEventUseCase recordLoginEvent;
  private final SocialIdentityLinkCompletion linkCompletion;
  private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

  // one parameter per collaborating port — same rationale as
  // AuthenticateWithSocialProviderService's
  // own identical suppression: this handler genuinely orchestrates both tiers' own use case +
  // session-establishment pairs, plus (TD-SEC-055) the same device-trust/session-task collaborators
  // every primary-factor controller already needs.
  @SuppressWarnings({"java:S107", "PMD.ExcessiveParameterList"})
  public SocialLoginAuthenticationSuccessHandler(
      final AuthenticateWithSocialProviderUseCase tenantUseCase,
      final AuthenticatePlatformAccountWithSocialProviderUseCase platformUseCase,
      final AuthenticatedSessionEstablisher tenantSessions,
      final PlatformAuthenticatedSessionEstablisher platformSessions,
      final RecordAccountLoginDeviceUseCase recordLoginDevice,
      final RedirectUrlResolver redirectUrlResolver,
      final AccountRepository accounts,
      final KnownDeviceRepository knownDevices,
      final RequestDeviceTrustChallengeUseCase requestDeviceTrustChallenge,
      final AccountAuthenticationPolicyProvider authenticationPolicyProvider,
      final RecordLoginEventUseCase recordLoginEvent,
      final SocialIdentityLinkCompletion linkCompletion) {
    this.tenantUseCase = tenantUseCase;
    this.platformUseCase = platformUseCase;
    this.tenantSessions = tenantSessions;
    this.platformSessions = platformSessions;
    this.recordLoginDevice = recordLoginDevice;
    this.redirectUrlResolver = redirectUrlResolver;
    this.accounts = accounts;
    this.knownDevices = knownDevices;
    this.requestDeviceTrustChallenge = requestDeviceTrustChallenge;
    this.authenticationPolicyProvider = authenticationPolicyProvider;
    this.recordLoginEvent = recordLoginEvent;
    this.linkCompletion = linkCompletion;
  }

  @Override
  public void onAuthenticationSuccess(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final Authentication authentication)
      throws IOException {
    final OAuth2AuthenticationToken oauthToken = (OAuth2AuthenticationToken) authentication;

    final HttpSession session = request.getSession(false);
    final String organizationIdValue =
        session == null
            ? null
            : (String)
                session.getAttribute(
                    SocialLoginRedirectController.ORGANIZATION_ID_SESSION_ATTRIBUTE);
    // Clerk "customize redirect URLs" parity: same single-use session round trip as
    // organizationIdValue above — see SocialLoginRedirectController's own Javadoc for why an
    // external provider round trip needs this at all.
    final String clientId =
        session == null
            ? null
            : (String)
                session.getAttribute(SocialLoginRedirectController.CLIENT_ID_SESSION_ATTRIBUTE);
    final String redirectUrl =
        session == null
            ? null
            : (String)
                session.getAttribute(SocialLoginRedirectController.REDIRECT_URL_SESSION_ATTRIBUTE);
    if (session != null) {
      session.removeAttribute(SocialLoginRedirectController.ORGANIZATION_ID_SESSION_ATTRIBUTE);
      session.removeAttribute(SocialLoginRedirectController.CLIENT_ID_SESSION_ATTRIBUTE);
      session.removeAttribute(SocialLoginRedirectController.REDIRECT_URL_SESSION_ATTRIBUTE);
    }

    final SocialProvider provider =
        SocialProviderProfile.resolveProvider(oauthToken.getAuthorizedClientRegistrationId());
    if (provider == null) {
      redirectStrategy.sendRedirect(request, response, errorRedirect(organizationIdValue));
      return;
    }
    final OAuth2User principal = oauthToken.getPrincipal();
    // TD-FUT-043: a signed-in End-User connecting a provider is not a sign-in at all.
    if (linkCompletion.completeIfRequested(
        request, response, session, organizationIdValue, provider, principal.getName())) {
      return;
    }
    // getName() resolves to whichever attribute each registration's own userNameAttributeName
    // names (sub for Google, id for GitHub) — the provider's own opaque, stable subject
    // identifier, uniformly, regardless of which OAuth2UserService produced this principal.
    final String providerUserId = principal.getName();

    final String verifiedEmail = SocialProviderProfile.resolveVerifiedEmail(provider, principal);
    if (verifiedEmail == null) {
      redirectStrategy.sendRedirect(request, response, errorRedirect(organizationIdValue));
      return;
    }

    if (organizationIdValue != null) {
      onTenantLogin(
          request,
          response,
          UUID.fromString(organizationIdValue),
          provider,
          providerUserId,
          verifiedEmail,
          clientId,
          redirectUrl,
          SocialProviderProfile.resolveSocialProfile(provider, principal));
    } else {
      onPlatformLogin(request, response, provider, providerUserId, verifiedEmail);
    }
  }

  @SuppressWarnings("java:S107") // two more parameters for Clerk "customize redirect URLs"
  // parity, plus ADR-0026's own socialProfile — same rationale as this class's own constructor
  // suppression.
  private void onTenantLogin(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final UUID organizationId,
      final SocialProvider provider,
      final String providerUserId,
      final String verifiedEmail,
      final String clientId,
      final String redirectUrl,
      final SocialProviderProfile.SocialProfile socialProfile)
      throws IOException {
    final AuthenticateWithSocialProviderResult result;
    try {
      result =
          tenantUseCase.handle(
              new AuthenticateWithSocialProviderCommand(
                  new OrganizationId(organizationId),
                  provider,
                  providerUserId,
                  new Email(verifiedEmail),
                  true,
                  socialProfile.firstName(),
                  socialProfile.lastName(),
                  socialProfile.username(),
                  socialProfile.pictureUrl()));
    } catch (final SocialLoginNotAllowedException
        | UnverifiedProviderEmailException
        | EmailSubaddressNotAllowedException _) {
      // ADR-0020 Decision 3/BR-ID-12: re-verified by the use case itself — a narrow TOCTOU window
      // between SocialLoginRedirectController's own pre-check and this point (an operator disabling
      // the provider mid-flow) lands here, same fail-closed outcome either way.
      redirectStrategy.sendRedirect(
          request, response, TENANT_PATH_PREFIX + organizationId + "/login?socialLoginError");
      return;
    }

    if (result
        instanceof
        AuthenticateWithSocialProviderResult.LoggedIn(
            AccountId accountId,
            boolean usernameNeeded)) {
      // TD-SEC-055: same DeviceTrustGate/SessionTaskGate sequence PrimaryFactorLoginCompletion
      // already runs for password/email-code/email-link logins — social login used to establish a
      // session directly below, through neither check, a live bypass of both a device-trust
      // step-up and an operator-forced password reset for any account with a linked social
      // identity. account is fetched once here (moments after tenantUseCase.handle(...) itself
      // just loaded/created the same row) rather than threading it back out of
      // AuthenticateWithSocialProviderResult.LoggedIn — a narrower, lower-blast-radius change than
      // widening that sealed result's own shape for every other caller.
      final Account account =
          accounts
              .findById(accountId)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Account vanished immediately after its own successful login: "
                              + accountId));

      final Optional<String> deviceChallenge =
          DeviceTrustGate.intercept(
              knownDevices,
              requestDeviceTrustChallenge,
              authenticationPolicyProvider.policyFor(new OrganizationId(organizationId)),
              request,
              organizationId,
              accountId,
              PendingAuthenticationFactor.SOCIAL,
              clientId,
              redirectUrl,
              account.bypassesDeviceTrust(),
              provider);
      if (deviceChallenge.isPresent()) {
        redirectStrategy.sendRedirect(request, response, deviceChallenge.get());
        return;
      }

      final Optional<String> sessionTask =
          SessionTaskGate.intercept(
              request,
              organizationId,
              account,
              PendingAuthenticationFactor.SOCIAL,
              clientId,
              redirectUrl,
              provider,
              usernameNeeded);
      if (sessionTask.isPresent()) {
        redirectStrategy.sendRedirect(request, response, sessionTask.get());
        return;
      }

      final String fallbackUrl =
          redirectUrlResolver
              .resolve(
                  new OrganizationId(organizationId), clientId, redirectUrl, RedirectAction.SIGN_IN)
              .orElse(TENANT_PATH_PREFIX + organizationId + "/login?authenticated");
      final String target =
          tenantSessions.establishViaSocialLogin(
              request, response, accountId.value(), provider, false, fallbackUrl);
      // New-device login email notification — same call LoginController's own password-login
      // path makes, right after establishing the session; see
      // RecordAccountLoginDeviceService's own Javadoc for why this never throws. A present
      // return value means an unrecognized/absent DeviceCookie just got a fresh one minted for
      // it — write it back onto the response so the browser actually keeps it.
      recordLoginDevice
          .handle(
              new RecordAccountLoginDeviceCommand(
                  accountId,
                  request.getHeader("User-Agent"),
                  request.getRemoteAddr(),
                  DeviceCookie.read(request, organizationId).orElse(null)))
          .ifPresent(
              rawDeviceToken ->
                  DeviceCookie.write(request, response, organizationId, rawDeviceToken));

      // TD-FUT-034: this handler never goes through AuthenticatedSessionCompletion (it owns both
      // the tenant and platform social-login branches directly) — same direct call
      // EmailLinkSignInController's own confirm() already makes. Tenant-tier branch only: the
      // "View Profile" activity heatmap this feeds is scoped to tenant Accounts, never the
      // platform tier below.
      recordLoginEvent.handle(
          new RecordLoginEventCommand(accountId, new OrganizationId(organizationId)));
      redirectStrategy.sendRedirect(request, response, target);
    } else if (result instanceof AuthenticateWithSocialProviderResult.PendingApproval) {
      // TD-FUT-019: same informational page RegisterAccountController's own gated signup redirect
      // targets — no email is known at this point to carry across the redirect (unlike that
      // controller's own form-submission path), so the page falls back to its generic copy.
      redirectStrategy.sendRedirect(
          request, response, TENANT_PATH_PREFIX + organizationId + "/register/pending-approval");
    } else {
      redirectStrategy.sendRedirect(
          request,
          response,
          TENANT_PATH_PREFIX + organizationId + "/login/social/confirmation-required");
    }
  }

  private void onPlatformLogin(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final SocialProvider provider,
      final String providerUserId,
      final String verifiedEmail)
      throws IOException {
    final AuthenticatePlatformAccountWithSocialProviderResult result =
        platformUseCase.handle(
            new AuthenticatePlatformAccountWithSocialProviderCommand(
                provider, providerUserId, new Email(verifiedEmail), true));

    if (result
        instanceof
        AuthenticatePlatformAccountWithSocialProviderResult.LoggedIn(
            PlatformAccountId platformAccountId)) {
      final String target =
          platformSessions.establish(
              request, response, platformAccountId.value(), "/platform/dashboard");
      redirectStrategy.sendRedirect(request, response, target);
    } else {
      redirectStrategy.sendRedirect(
          request, response, "/platform/login/social/confirmation-required");
    }
  }

  private String errorRedirect(final String organizationIdValue) {
    return organizationIdValue == null
        ? "/platform/login?socialLoginError"
        : TENANT_PATH_PREFIX + organizationIdValue + "/login?socialLoginError";
  }
}
