package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.authenticatewithpassword.EmailNotVerifiedException;
import com.clavaris.identity.application.usecase.authenticatewithpassword.InvalidCredentialsException;
import com.clavaris.identity.application.usecase.authenticatewithusername.AuthenticateWithUsernameCommand;
import com.clavaris.identity.application.usecase.authenticatewithusername.AuthenticateWithUsernameUseCase;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.KnownDeviceRepository;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceCommand;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceUseCase;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestdevicetrustchallenge.RequestDeviceTrustChallengeUseCase;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectAction;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectUrlResolver;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.Username;
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
 * ADR-0024 §4: username sign-in — {@code organizationId} from the path, never a form field, same
 * BR-ORG-02 rationale as every other controller in this package. A dedicated route rather than
 * merging username into {@link LoginController}'s own email field: same "one method, one route"
 * pattern this codebase already established for the two passwordless email methods ({@link
 * EmailCodeSignInController}/{@link EmailLinkSignInController}) rather than one combined,
 * mode-switching form.
 */
// Same class-level-suppression rationale as LoginController's own identical annotation.
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/o/{organizationId}/login/username")
public class UsernameSignInController {

  private static final String FORM_VIEW = "identity/login-username";

  private final AuthenticateWithUsernameUseCase useCase;
  private final AuthenticatedSessionEstablisher sessions;
  private final RecordAccountLoginDeviceUseCase recordLoginDevice;
  private final KnownDeviceRepository knownDevices;
  private final AccountAuthenticationPolicyProvider authenticationPolicyProvider;
  private final RequestDeviceTrustChallengeUseCase requestDeviceTrustChallenge;
  private final RedirectUrlResolver redirectUrlResolver;
  private final AccountRepository accounts;

  @SuppressWarnings("java:S107")
  public UsernameSignInController(
      final AuthenticateWithUsernameUseCase useCase,
      final AuthenticatedSessionEstablisher sessions,
      final RecordAccountLoginDeviceUseCase recordLoginDevice,
      final KnownDeviceRepository knownDevices,
      final AccountAuthenticationPolicyProvider authenticationPolicyProvider,
      final RequestDeviceTrustChallengeUseCase requestDeviceTrustChallenge,
      final RedirectUrlResolver redirectUrlResolver,
      final AccountRepository accounts) {
    this.useCase = useCase;
    this.sessions = sessions;
    this.recordLoginDevice = recordLoginDevice;
    this.knownDevices = knownDevices;
    this.authenticationPolicyProvider = authenticationPolicyProvider;
    this.requestDeviceTrustChallenge = requestDeviceTrustChallenge;
    this.redirectUrlResolver = redirectUrlResolver;
    this.accounts = accounts;
  }

  @GetMapping
  public String showForm(@PathVariable final UUID organizationId, final Model model) {
    model.addAttribute("form", new UsernamePasswordForm());
    return FORM_VIEW;
  }

  // Same "one exit per distinct rejection reason" rationale as LoginController's own suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String login(
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute("form") final UsernamePasswordForm form,
      final BindingResult bindingResult,
      final HttpServletRequest request,
      final HttpServletResponse response,
      final Model model,
      // Clerk "customize redirect URLs" parity — see LoginController's own identical parameters.
      @RequestParam(required = false) final String clientId,
      @RequestParam(required = false) final String redirectUrl) {
    if (bindingResult.hasErrors()) {
      return FORM_VIEW;
    }

    final AccountId accountId;
    try {
      accountId =
          useCase.handle(
              new AuthenticateWithUsernameCommand(
                  new OrganizationId(organizationId),
                  new Username(form.getUsername()),
                  form.getPassword()));
    } catch (final IllegalArgumentException | InvalidCredentialsException _) {
      // Username's own domain constructor rejects a shape the form's plain size check wouldn't
      // catch, such as whitespace-only input surviving trimming — same anti-enumeration-generic
      // outcome as an actual InvalidCredentialsException, not a distinguishable field error, so
      // both collapse to the same rendering.
      model.addAttribute("loginError", true);
      return FORM_VIEW;
    } catch (final EmailNotVerifiedException _) {
      model.addAttribute("emailNotVerifiedError", true);
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
