package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.authenticatewithpassword.EmailNotVerifiedException;
import com.clavaris.identity.application.usecase.authenticatewithpassword.InvalidCredentialsException;
import com.clavaris.identity.application.usecase.authenticatewithpassword.VerificationOverloadedException;
import com.clavaris.identity.application.usecase.authenticatewithusername.AuthenticateWithUsernameCommand;
import com.clavaris.identity.application.usecase.authenticatewithusername.AuthenticateWithUsernameUseCase;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.KnownDeviceRepository;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceUseCase;
import com.clavaris.identity.application.usecase.requestdevicetrustchallenge.RequestDeviceTrustChallengeUseCase;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectUrlResolver;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.Username;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
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

  // TD-ARCH-016: sessions/recordLoginDevice/knownDevices/authenticationPolicyProvider/
  // requestDeviceTrustChallenge/redirectUrlResolver are never read as bare fields anywhere in this
  // class — they exist solely to build this one record, once, here, not per-request. Kept as
  // constructor parameters (not folded away) so Spring still autowires each of them individually,
  // the same as before this extraction.
  private final PrimaryFactorLoginPorts loginPorts;

  @SuppressWarnings("java:S107")
  public UsernameSignInController(
      final AuthenticateWithUsernameUseCase useCase,
      final AuthenticatedSessionEstablisher sessions,
      final RecordAccountLoginDeviceUseCase recordLoginDevice,
      final KnownDeviceRepository knownDevices,
      final AccountAuthenticationPolicyProvider authenticationPolicyProvider,
      final RequestDeviceTrustChallengeUseCase requestDeviceTrustChallenge,
      final RedirectUrlResolver redirectUrlResolver) {
    this.useCase = useCase;
    this.loginPorts =
        new PrimaryFactorLoginPorts(
            knownDevices,
            requestDeviceTrustChallenge,
            authenticationPolicyProvider,
            sessions,
            recordLoginDevice,
            redirectUrlResolver);
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

    final Account account;
    try {
      account =
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
    } catch (final VerificationOverloadedException _) {
      // TD-FUT-017: same rationale as LoginController's own identical catch block — the password
      // was never actually checked, so this must never be rendered as loginError's generic
      // "invalid credentials" message.
      response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      model.addAttribute("serviceOverloadedError", true);
      return FORM_VIEW;
    }

    // TD-ARCH-016 (closed): used to be an identical, byte-for-byte-duplicated block against
    // LoginController's own equivalent — see PrimaryFactorLoginCompletion's own Javadoc.
    return PrimaryFactorLoginCompletion.completeAfterPrimaryFactor(
        loginPorts,
        request,
        response,
        organizationId,
        account,
        PendingAuthenticationFactor.PASSWORD,
        clientId,
        redirectUrl);
  }
}
