package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.authenticatewithemaillink.AuthenticateWithEmailLinkCommand;
import com.clavaris.identity.application.usecase.authenticatewithemaillink.AuthenticateWithEmailLinkUseCase;
import com.clavaris.identity.application.usecase.authenticatewithemaillink.InvalidSignInLinkException;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.KnownDeviceRepository;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceCommand;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceUseCase;
import com.clavaris.identity.application.usecase.requestdevicetrustchallenge.RequestDeviceTrustChallengeUseCase;
import com.clavaris.identity.application.usecase.requestemailsigninlink.RequestEmailSignInLinkCommand;
import com.clavaris.identity.application.usecase.requestemailsigninlink.RequestEmailSignInLinkUseCase;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
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
 * ADR-0024 §3: passwordless email link sign-in — {@code organizationId} from the path, never a form
 * field, same BR-ORG-02 rationale as every other controller in this package.
 *
 * <p><b>Deliberately not auto-completed on the bare {@code GET} of the emailed link</b> — a known
 * magic-link pitfall: email scanners/prefetchers (corporate security gateways, some mail clients'
 * own "safe link" rewriting) fetch a link's {@code GET} automatically before a human ever sees it,
 * which would silently burn a genuinely single-use sign-in token. {@code GET} here only ever
 * renders a same-origin confirmation page with a CSRF-protected {@code POST} button — the actual
 * authentication only happens on that deliberate, human-initiated {@code POST}, same reasoning
 * {@link ResetPasswordController}'s own hidden-token round trip already establishes for a
 * different, but structurally identical, class of one-click-link risk.
 */
// Same rationale as EmailCodeSignInController's own identical class-level suppression.
@SuppressWarnings({"PMD.LongVariable", "PMD.AvoidDuplicateLiterals"})
@Controller
@RequestMapping("/o/{organizationId}/login/email-link")
public class EmailLinkSignInController {

  private static final String REQUEST_FORM_VIEW = "identity/login-email-link-request";
  private static final String CONFIRM_FORM_VIEW = "identity/login-email-link-confirm";

  private final RequestEmailSignInLinkUseCase requestUseCase;
  private final AuthenticateWithEmailLinkUseCase authenticateUseCase;
  private final AuthenticatedSessionEstablisher sessions;
  private final RecordAccountLoginDeviceUseCase recordLoginDevice;
  private final KnownDeviceRepository knownDevices;
  private final AccountAuthenticationPolicyProvider authenticationPolicyProvider;
  private final RequestDeviceTrustChallengeUseCase requestDeviceTrustChallenge;

  @SuppressWarnings("java:S107")
  public EmailLinkSignInController(
      final RequestEmailSignInLinkUseCase requestUseCase,
      final AuthenticateWithEmailLinkUseCase authenticateUseCase,
      final AuthenticatedSessionEstablisher sessions,
      final RecordAccountLoginDeviceUseCase recordLoginDevice,
      final KnownDeviceRepository knownDevices,
      final AccountAuthenticationPolicyProvider authenticationPolicyProvider,
      final RequestDeviceTrustChallengeUseCase requestDeviceTrustChallenge) {
    this.requestUseCase = requestUseCase;
    this.authenticateUseCase = authenticateUseCase;
    this.sessions = sessions;
    this.recordLoginDevice = recordLoginDevice;
    this.knownDevices = knownDevices;
    this.authenticationPolicyProvider = authenticationPolicyProvider;
    this.requestDeviceTrustChallenge = requestDeviceTrustChallenge;
  }

  @GetMapping
  public String showRequestForm(@PathVariable final UUID organizationId, final Model model) {
    model.addAttribute("form", new RequestEmailSignInLinkForm());
    return REQUEST_FORM_VIEW;
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String requestLink(
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute("form") final RequestEmailSignInLinkForm form,
      final BindingResult bindingResult) {
    if (bindingResult.hasErrors()) {
      return REQUEST_FORM_VIEW;
    }

    // Same always-redirect-regardless-of-found anti-enumeration posture as
    // ForgotPasswordController.
    requestUseCase.handle(
        new RequestEmailSignInLinkCommand(
            new OrganizationId(organizationId), new Email(form.getEmail())));

    return "redirect:/o/" + organizationId + "/login/email-link/pending";
  }

  @GetMapping("/pending")
  public String pending() {
    return "identity/login-email-link-pending";
  }

  @GetMapping("/confirm")
  public String showConfirmForm(
      @PathVariable final UUID organizationId,
      @RequestParam final String token,
      final Model model) {
    final AuthenticateWithEmailLinkForm form = new AuthenticateWithEmailLinkForm();
    form.setToken(token);
    model.addAttribute("form", form);
    return CONFIRM_FORM_VIEW;
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/confirm")
  public String confirm(
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute("form") final AuthenticateWithEmailLinkForm form,
      final BindingResult bindingResult,
      final HttpServletRequest request,
      final HttpServletResponse response) {
    if (bindingResult.hasErrors()) {
      return "identity/verification-link-invalid";
    }

    final AccountId accountId;
    try {
      accountId =
          authenticateUseCase.handle(
              new AuthenticateWithEmailLinkCommand(
                  new OrganizationId(organizationId), form.getToken()));
    } catch (final InvalidSignInLinkException _) {
      return "identity/verification-link-invalid";
    }

    final Optional<String> challenge =
        DeviceTrustGate.intercept(
            knownDevices,
            requestDeviceTrustChallenge,
            authenticationPolicyProvider.policyFor(new OrganizationId(organizationId)),
            request,
            organizationId,
            accountId,
            PendingAuthenticationFactor.ONE_TIME_EMAIL_PROOF);
    if (challenge.isPresent()) {
      return "redirect:" + challenge.get();
    }

    final String fallbackUrl = "/o/" + organizationId + "/login?authenticated";
    final String redirectTarget =
        sessions.establishViaOneTimeEmailProof(request, response, accountId.value(), fallbackUrl);

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
