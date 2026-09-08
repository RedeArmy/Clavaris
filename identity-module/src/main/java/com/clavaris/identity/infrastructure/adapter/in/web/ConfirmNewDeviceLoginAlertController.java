package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.confirmnewdeviceloginalert.ConfirmNewDeviceLoginAlertCommand;
import com.clavaris.identity.application.usecase.confirmnewdeviceloginalert.ConfirmNewDeviceLoginAlertUseCase;
import com.clavaris.identity.application.usecase.confirmnewdeviceloginalert.InvalidNewDeviceLoginAlertException;
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
 * TD-FUT-025: the "this wasn't me" link a new-device-login notification email sends. {@code
 * organizationId} from the path, never a form field — same BR-ORG-02 rationale as every other
 * controller in this package.
 *
 * <p><b>Deliberately not auto-completed on the bare {@code GET} of the emailed link</b> — same
 * email-scanner/prefetcher risk {@link EmailLinkSignInController}'s own Javadoc documents, but
 * higher stakes here: this link's action is destructive (locks the account, signs out every
 * session), not merely authenticating one. {@code GET} only ever renders a same-origin confirmation
 * page with a CSRF-protected {@code POST} button; the account is only ever locked on that
 * deliberate, human-initiated {@code POST}. Not the simpler bare-{@code GET} pattern {@link
 * ConfirmSocialLinkController} uses — that action is benign/additive (linking a social identity),
 * this one is not.
 *
 * <p>No session is established or required either way — unlike {@link EmailLinkSignInController},
 * this is not a sign-in flow, so there is nothing here resembling {@code
 * AuthenticatedSessionEstablisher} or device-cookie bookkeeping.
 */
@Controller
@RequestMapping("/o/{organizationId}/account-alert/lock")
public class ConfirmNewDeviceLoginAlertController {

  private static final String CONFIRM_VIEW = "identity/account-alert-lock-confirm";
  private static final String INVALID_VIEW = "identity/verification-link-invalid";

  private final ConfirmNewDeviceLoginAlertUseCase useCase;

  public ConfirmNewDeviceLoginAlertController(final ConfirmNewDeviceLoginAlertUseCase useCase) {
    this.useCase = useCase;
  }

  @GetMapping
  public String showConfirmForm(
      @PathVariable final UUID organizationId,
      @RequestParam final String token,
      final Model model) {
    final ConfirmNewDeviceLoginAlertForm form = new ConfirmNewDeviceLoginAlertForm();
    form.setToken(token);
    model.addAttribute("form", form);
    return CONFIRM_VIEW;
  }

  // Same "several independent rejection reasons, each needs its own exit" rationale as
  // ResetPasswordController's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String confirm(
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute("form") final ConfirmNewDeviceLoginAlertForm form,
      final BindingResult bindingResult) {
    if (bindingResult.hasErrors()) {
      return INVALID_VIEW;
    }

    try {
      useCase.handle(new ConfirmNewDeviceLoginAlertCommand(form.getToken()));
    } catch (final InvalidNewDeviceLoginAlertException _) {
      return INVALID_VIEW;
    }

    return "redirect:/o/" + organizationId + "/account-alert/lock/success";
  }

  @GetMapping("/success")
  public String success() {
    return "identity/account-alert-lock-success";
  }
}
