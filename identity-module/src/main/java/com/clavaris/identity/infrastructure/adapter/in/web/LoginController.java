package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.authenticatewithpassword.AuthenticateWithPasswordCommand;
import com.clavaris.identity.application.usecase.authenticatewithpassword.AuthenticateWithPasswordUseCase;
import com.clavaris.identity.application.usecase.authenticatewithpassword.EmailNotVerifiedException;
import com.clavaris.identity.application.usecase.authenticatewithpassword.InvalidCredentialsException;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.OrganizationSocialLoginPolicyProvider;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.KnownDeviceRepository;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceCommand;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceUseCase;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestdevicetrustchallenge.RequestDeviceTrustChallengeUseCase;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicySnapshot;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectAction;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectUrlResolver;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SocialProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The hosted login page for the interactive Authorization Code flow (ADR-0010 §5) — this is what
 * {@code app}'s {@code OrganizationAuthorizationServerConfig} redirects an unauthenticated {@code
 * /oauth2/authorize} request to. Same "{@code organizationId} from the path, never a form field"
 * rationale as {@link RegisterAccountController}: BR-ORG-02 requires this screen to only ever be
 * capable of authenticating against the one Organization its own URL already names.
 *
 * <p>Deliberately does not touch Spring Security's own {@code SecurityContext}/{@code
 * Authentication} types directly — see {@link AuthenticatedSessionEstablisher}'s own Javadoc for
 * why that's a bridge to {@code app}, not something this module does itself.
 *
 * <p>ADR-0020 Decision 3, BR-ID-12: {@code socialProviders} in the model is this Organization's own
 * currently-enabled provider list, computed fresh on every render (never cached) by asking {@link
 * OrganizationSocialLoginPolicyProvider} about each known {@link SocialProvider} in turn — the
 * template only ever renders a "Sign in with X" link for a provider actually in that list.
 * Iterating {@code SocialProvider.values()} rather than a hardcoded pair also means a future
 * provider (Microsoft, {@code TD-FUT-022}) needs no controller change to start appearing here the
 * day it's added to the enum.
 */
// PMD.LongVariable: authenticationPolicyProvider/requestDeviceTrustChallenge (field + constructor
// param each) are long by design, not accidentally — same class-level-suppression precedent as
// RecordAccountLoginDeviceService's own identical rationale, chosen over 4 individual suppressions
// once PMD.AvoidDuplicateLiterals started flagging that many repeats of the same string.
// PMD.ExcessiveImports: this controller now collaborates with 7 ports (device trust, ADR-0024 §6,
// added 2 more) — same "one import per collaborating type is inherent to the design, not a code
// smell" rationale as OrganizationAuthorizationServerConfig's own identical suppression.
@SuppressWarnings({"PMD.LongVariable", "PMD.ExcessiveImports"})
@Controller
@RequestMapping("/o/{organizationId}/login")
public class LoginController {

  private static final String FORM_VIEW = "identity/login";

  private final AuthenticateWithPasswordUseCase useCase;
  private final AuthenticatedSessionEstablisher sessions;
  private final OrganizationSocialLoginPolicyProvider policyProvider;
  private final RecordAccountLoginDeviceUseCase recordLoginDevice;
  private final KnownDeviceRepository knownDevices;
  private final AccountAuthenticationPolicyProvider authenticationPolicyProvider;
  private final RequestDeviceTrustChallengeUseCase requestDeviceTrustChallenge;
  private final RedirectUrlResolver redirectUrlResolver;
  private final AccountRepository accounts;

  @SuppressWarnings("java:S107")
  public LoginController(
      final AuthenticateWithPasswordUseCase useCase,
      final AuthenticatedSessionEstablisher sessions,
      final OrganizationSocialLoginPolicyProvider policyProvider,
      final RecordAccountLoginDeviceUseCase recordLoginDevice,
      final KnownDeviceRepository knownDevices,
      final AccountAuthenticationPolicyProvider authenticationPolicyProvider,
      final RequestDeviceTrustChallengeUseCase requestDeviceTrustChallenge,
      final RedirectUrlResolver redirectUrlResolver,
      final AccountRepository accounts) {
    this.useCase = useCase;
    this.sessions = sessions;
    this.policyProvider = policyProvider;
    this.recordLoginDevice = recordLoginDevice;
    this.knownDevices = knownDevices;
    this.authenticationPolicyProvider = authenticationPolicyProvider;
    this.requestDeviceTrustChallenge = requestDeviceTrustChallenge;
    this.redirectUrlResolver = redirectUrlResolver;
    this.accounts = accounts;
  }

  @GetMapping
  public String showForm(
      @PathVariable final UUID organizationId,
      final Model model,
      // Clerk "customize redirect URLs" parity: needed here (unlike the POST handler below, which
      // relies on the form's own th:action="@{''}" resubmit to carry these forward) purely so the
      // social-login link — a plain <a href>, not a form resubmission — can append them to its own
      // URL; see login.html's own comment.
      @RequestParam(required = false) final String clientId,
      @RequestParam(required = false) final String redirectUrl) {
    model.addAttribute("form", new LoginForm());
    addSignInOptions(organizationId, model, clientId, redirectUrl);
    return FORM_VIEW;
  }

  // Two independent rejection reasons (form validation, invalid credentials) each need their own
  // exit — same rationale as RegisterAccountController's own suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String login(
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute("form") final LoginForm form,
      final BindingResult bindingResult,
      final HttpServletRequest request,
      final HttpServletResponse response,
      final Model model,
      // Clerk "customize redirect URLs" parity: survive here purely because the form's own
      // th:action="@{''}" resubmits to this exact same URL, query string included — no hidden
      // field needed. Both optional: absent on every request that never opted into this feature.
      @RequestParam(required = false) final String clientId,
      @RequestParam(required = false) final String redirectUrl) {
    if (bindingResult.hasErrors()) {
      addSignInOptions(organizationId, model, clientId, redirectUrl);
      return FORM_VIEW;
    }

    final AccountId accountId;
    try {
      accountId =
          useCase.handle(
              new AuthenticateWithPasswordCommand(
                  new OrganizationId(organizationId),
                  new Email(form.getEmail()),
                  form.getPassword()));
    } catch (final InvalidCredentialsException _) {
      // Deliberately one single, generic, form-level error — never field-scoped (that would
      // itself leak "the email field was fine, it was the password" or vice versa), matching
      // InvalidCredentialsException's own anti-enumeration design.
      model.addAttribute("loginError", true);
      addSignInOptions(organizationId, model, clientId, redirectUrl);
      return FORM_VIEW;
    } catch (final EmailNotVerifiedException _) {
      // ADR-0024 §2: deliberately a distinct, more specific message than loginError above — see
      // EmailNotVerifiedException's own Javadoc for why this one case is allowed to differ from
      // the anti-enumeration-generic rejection every other failure mode uses.
      model.addAttribute("emailNotVerifiedError", true);
      addSignInOptions(organizationId, model, clientId, redirectUrl);
      return FORM_VIEW;
    }

    final Optional<String> challenge =
        DeviceTrustGate.intercept(
            knownDevices,
            requestDeviceTrustChallenge,
            authenticationPolicyProvider.policyFor(new OrganizationId(organizationId)),
            request,
            organizationId,
            accountId,
            PendingAuthenticationFactor.PASSWORD,
            clientId,
            redirectUrl);
    if (challenge.isPresent()) {
      return "redirect:" + challenge.get();
    }

    final Optional<String> sessionTask =
        SessionTaskGate.intercept(
            accounts,
            request,
            organizationId,
            accountId,
            PendingAuthenticationFactor.PASSWORD,
            clientId,
            redirectUrl);
    if (sessionTask.isPresent()) {
      return "redirect:" + sessionTask.get();
    }

    final String fallbackUrl =
        redirectUrlResolver
            .resolve(
                new OrganizationId(organizationId), clientId, redirectUrl, RedirectAction.SIGN_IN)
            .orElse("/o/" + organizationId + "/login?authenticated");
    final String redirectTarget =
        sessions.establish(request, response, accountId.value(), fallbackUrl);

    // New-device login email notification — after establish(), same accountId/request already in
    // scope; see RecordAccountLoginDeviceService's own Javadoc for why this never throws. A
    // present return value means an unrecognized/absent DeviceCookie just got a fresh one minted
    // for it — write it back onto the response so the browser actually keeps it.
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

    return "redirect:" + redirectTarget;
  }

  // Code review finding (TD-SEC-032, closed): one allowedProviders() call per render, not one
  // isProviderAllowed() call per known SocialProvider — see that port method's own Javadoc for
  // why this reduction doesn't weaken BR-ID-12 (the requirement that call re-verifies is
  // AuthenticateWithSocialProviderService's own single-provider check at the moment a login is
  // actually attempted, never how many times a page render reads the same enabled-providers set).
  //
  // ADR-0024 §3/§4: also surfaces which passwordless/username strategies this Organization has
  // enabled — same "computed fresh on every render, never cached, template only renders what's
  // actually allowed" posture as the social-provider list above.
  private void addSignInOptions(
      final UUID organizationId,
      final Model model,
      // Clerk "customize redirect URLs" parity: threaded through purely so the social-login link
      // (a plain <a href>, not a form resubmission) can append them to its own URL — see
      // login.html's own comment. Both nullable; Thymeleaf's link-expression syntax omits a param
      // entirely when its value is null.
      final String clientId,
      final String redirectUrl) {
    final OrganizationId orgId = new OrganizationId(organizationId);
    final List<SocialProvider> enabled = new ArrayList<>(policyProvider.allowedProviders(orgId));
    model.addAttribute("socialProviders", enabled);

    final AccountAuthenticationPolicySnapshot policy =
        authenticationPolicyProvider.policyFor(orgId);
    model.addAttribute("emailCodeSignInEnabled", policy.emailCodeSignInEnabled());
    model.addAttribute("emailLinkSignInEnabled", policy.emailLinkSignInEnabled());
    model.addAttribute("usernameSignInEnabled", policy.usernameSignInEnabled());
    model.addAttribute("clientId", clientId);
    model.addAttribute("redirectUrl", redirectUrl);
  }
}
