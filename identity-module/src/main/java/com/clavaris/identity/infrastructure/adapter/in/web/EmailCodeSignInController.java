package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.authenticatewithemailcode.AuthenticateWithEmailCodeCommand;
import com.clavaris.identity.application.usecase.authenticatewithemailcode.AuthenticateWithEmailCodeUseCase;
import com.clavaris.identity.application.usecase.authenticatewithemailcode.InvalidOneTimeCodeException;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.KnownDeviceRepository;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceCommand;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceUseCase;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestdevicetrustchallenge.RequestDeviceTrustChallengeUseCase;
import com.clavaris.identity.application.usecase.requestemailsignincode.RequestEmailSignInCodeCommand;
import com.clavaris.identity.application.usecase.requestemailsignincode.RequestEmailSignInCodeUseCase;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectAction;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectUrlResolver;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
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
 * ADR-0024 §3: passwordless email code sign-in — {@code organizationId} from the path, never a form
 * field, same BR-ORG-02 rationale as every other controller in this package. Two-step flow
 * mirroring {@link ForgotPasswordController}/{@link ResetPasswordController}'s own request/confirm
 * split: {@code email} travels as a hidden field (query parameter on the redirect, then a form
 * field) across the two steps, same "what the user submits is exactly what was rendered" reasoning
 * {@code ConfirmPasswordResetForm}'s own {@code token} field already establishes — no server-side
 * session state needed for this handoff.
 */
// PMD.LongVariable: authenticateUseCase distinguishes itself from requestUseCase — a shortened
// name would only make this class's two collaborating use cases harder to tell apart.
// PMD.AvoidDuplicateLiterals: "form" is the Thymeleaf model attribute name every controller in
// this package uses for its bound form object — a named constant here would only add indirection.
// PMD.ExcessiveImports: this controller now collaborates with 9 ports (Clerk feature builds keep
// adding one apiece) — same "one import per collaborating type is inherent to the design, not a
// code smell" rationale as LoginController's own identical suppression.
@SuppressWarnings({"PMD.LongVariable", "PMD.AvoidDuplicateLiterals", "PMD.ExcessiveImports"})
@Controller
@RequestMapping("/o/{organizationId}/login/email-code")
public class EmailCodeSignInController {

  private static final String REQUEST_FORM_VIEW = "identity/login-email-code-request";
  private static final String CONFIRM_FORM_VIEW = "identity/login-email-code-confirm";

  private final RequestEmailSignInCodeUseCase requestUseCase;
  private final AuthenticateWithEmailCodeUseCase authenticateUseCase;
  private final AuthenticatedSessionEstablisher sessions;
  private final RecordAccountLoginDeviceUseCase recordLoginDevice;
  private final KnownDeviceRepository knownDevices;
  private final AccountAuthenticationPolicyProvider authenticationPolicyProvider;
  private final RequestDeviceTrustChallengeUseCase requestDeviceTrustChallenge;
  private final RedirectUrlResolver redirectUrlResolver;
  private final AccountRepository accounts;

  @SuppressWarnings("java:S107")
  public EmailCodeSignInController(
      final RequestEmailSignInCodeUseCase requestUseCase,
      final AuthenticateWithEmailCodeUseCase authenticateUseCase,
      final AuthenticatedSessionEstablisher sessions,
      final RecordAccountLoginDeviceUseCase recordLoginDevice,
      final KnownDeviceRepository knownDevices,
      final AccountAuthenticationPolicyProvider authenticationPolicyProvider,
      final RequestDeviceTrustChallengeUseCase requestDeviceTrustChallenge,
      final RedirectUrlResolver redirectUrlResolver,
      final AccountRepository accounts) {
    this.requestUseCase = requestUseCase;
    this.authenticateUseCase = authenticateUseCase;
    this.sessions = sessions;
    this.recordLoginDevice = recordLoginDevice;
    this.knownDevices = knownDevices;
    this.authenticationPolicyProvider = authenticationPolicyProvider;
    this.requestDeviceTrustChallenge = requestDeviceTrustChallenge;
    this.redirectUrlResolver = redirectUrlResolver;
    this.accounts = accounts;
  }

  @GetMapping
  public String showRequestForm(@PathVariable final UUID organizationId, final Model model) {
    model.addAttribute("form", new RequestEmailSignInCodeForm());
    return REQUEST_FORM_VIEW;
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String requestCode(
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute("form") final RequestEmailSignInCodeForm form,
      final BindingResult bindingResult,
      // Clerk "customize redirect URLs" parity: this hop is a genuine cross-URL redirect (unlike
      // LoginController's own same-URL resubmit), so these are explicitly carried forward via
      // RedirectQueryParams rather than relying on the browser's own query-string preservation.
      @RequestParam(required = false) final String clientId,
      @RequestParam(required = false) final String redirectUrl) {
    if (bindingResult.hasErrors()) {
      return REQUEST_FORM_VIEW;
    }

    // Same always-redirect-regardless-of-found anti-enumeration posture as ForgotPasswordController
    // — EmailCodeSignInNotEnabledException (the Organization itself doesn't offer this method) is
    // the one exception deliberately NOT caught here: that's public information the login page
    // already reveals by whether it links here at all, not an account-specific secret.
    requestUseCase.handle(
        new RequestEmailSignInCodeCommand(
            new OrganizationId(organizationId), new Email(form.getEmail())));

    String target =
        "redirect:/o/" + organizationId + "/login/email-code/confirm?email=" + form.getEmail();
    target = RedirectQueryParams.appendIfPresent(target, "clientId", clientId);
    target = RedirectQueryParams.appendIfPresent(target, "redirectUrl", redirectUrl);
    return target;
  }

  @GetMapping("/confirm")
  public String showConfirmForm(
      @PathVariable final UUID organizationId,
      @RequestParam final String email,
      final Model model) {
    final AuthenticateWithEmailCodeForm form = new AuthenticateWithEmailCodeForm();
    form.setEmail(email);
    model.addAttribute("form", form);
    return CONFIRM_FORM_VIEW;
  }

  // Three genuinely distinct exits (validation error, invalid code, success) — same rationale as
  // ResetPasswordController's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/confirm")
  public String confirm(
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute("form") final AuthenticateWithEmailCodeForm form,
      final BindingResult bindingResult,
      final HttpServletRequest request,
      final HttpServletResponse response,
      final Model model,
      // Bound from the query string the redirect above appended — the confirm page's own
      // th:action="@{''}" resubmit then carries them forward automatically, same as
      // LoginController's own single-hop case.
      @RequestParam(required = false) final String clientId,
      @RequestParam(required = false) final String redirectUrl) {
    if (bindingResult.hasErrors()) {
      return CONFIRM_FORM_VIEW;
    }

    final AccountId accountId;
    try {
      accountId =
          authenticateUseCase.handle(
              new AuthenticateWithEmailCodeCommand(
                  new OrganizationId(organizationId), new Email(form.getEmail()), form.getCode()));
    } catch (final InvalidOneTimeCodeException _) {
      model.addAttribute("codeError", true);
      return CONFIRM_FORM_VIEW;
    }

    final Optional<String> challenge =
        DeviceTrustGate.intercept(
            knownDevices,
            requestDeviceTrustChallenge,
            authenticationPolicyProvider.policyFor(new OrganizationId(organizationId)),
            request,
            organizationId,
            accountId,
            PendingAuthenticationFactor.ONE_TIME_EMAIL_PROOF,
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
            PendingAuthenticationFactor.ONE_TIME_EMAIL_PROOF,
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
        sessions.establishViaOneTimeEmailProof(request, response, accountId.value(), fallbackUrl);

    // Same device-recording step LoginController's own POST handler already performs — see that
    // class's own Javadoc for why this never throws.
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
}
