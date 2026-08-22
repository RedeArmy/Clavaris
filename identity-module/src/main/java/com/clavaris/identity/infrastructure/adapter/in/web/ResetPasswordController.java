package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.confirmpasswordreset.ConfirmPasswordResetCommand;
import com.clavaris.identity.application.usecase.confirmpasswordreset.ConfirmPasswordResetUseCase;
import com.clavaris.identity.application.usecase.confirmpasswordreset.InvalidVerificationTokenException;
import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
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
 * The link a password-reset email sends the user to. {@code organizationId} from the path, never a
 * form field — same BR-ORG-02 rationale as every other controller in this package. Unlike {@link
 * VerifyEmailController}, this is a real form (a new password to collect), so the token travels as
 * a hidden field across the {@code GET}→{@code POST} round trip rather than staying a bare query
 * parameter throughout.
 */
@Controller
@RequestMapping("/o/{organizationId}/reset-password")
public class ResetPasswordController {

  private static final String FORM_VIEW = "identity/reset-password";

  private final ConfirmPasswordResetUseCase useCase;

  public ResetPasswordController(final ConfirmPasswordResetUseCase useCase) {
    this.useCase = useCase;
  }

  @GetMapping
  public String showForm(
      @PathVariable final UUID organizationId,
      @RequestParam final String token,
      final Model model) {
    final ConfirmPasswordResetForm form = new ConfirmPasswordResetForm();
    form.setToken(token);
    model.addAttribute("form", form);
    return FORM_VIEW;
  }

  // Same "several independent rejection reasons, each needs its own exit" rationale as
  // RegisterAccountController's own suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String resetPassword(
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute("form") final ConfirmPasswordResetForm form,
      final BindingResult bindingResult) {
    if (bindingResult.hasErrors()) {
      return FORM_VIEW;
    }

    if (!form.getNewPassword().equals(form.getConfirmPassword())) {
      bindingResult.rejectValue(
          "confirmPassword", "confirmPassword.mismatch", "Passwords do not match");
      return FORM_VIEW;
    }

    try {
      useCase.handle(new ConfirmPasswordResetCommand(form.getToken(), form.getNewPassword()));
    } catch (final InvalidVerificationTokenException _) {
      return "identity/verification-link-invalid";
    } catch (final WeakPasswordException _) {
      bindingResult.rejectValue(
          "newPassword", "newPassword.tooWeak", "Password does not meet the minimum requirements");
      return FORM_VIEW;
    }

    return "redirect:/o/" + organizationId + "/reset-password/success";
  }

  @GetMapping("/success")
  public String success() {
    return "identity/reset-password-success";
  }
}
