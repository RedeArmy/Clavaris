package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.confirmplatformaccountpasswordreset.ConfirmPlatformAccountPasswordResetCommand;
import com.clavaris.identity.application.usecase.confirmplatformaccountpasswordreset.ConfirmPlatformAccountPasswordResetUseCase;
import com.clavaris.identity.application.usecase.confirmplatformaccountpasswordreset.InvalidVerificationTokenException;
import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Mirrors {@link ResetPasswordController}, no {@code organizationId} in the path. Reuses {@link
 * ConfirmPasswordResetForm} as-is (no tenant-specific field on it).
 */
@Controller
@RequestMapping("/platform/reset-password")
public class ResetPlatformAccountPasswordController {

  private static final String FORM_VIEW = "identity/platform/reset-password";

  private final ConfirmPlatformAccountPasswordResetUseCase useCase;

  public ResetPlatformAccountPasswordController(
      final ConfirmPlatformAccountPasswordResetUseCase useCase) {
    this.useCase = useCase;
  }

  @GetMapping
  public String showForm(@RequestParam final String token, final Model model) {
    final ConfirmPasswordResetForm form = new ConfirmPasswordResetForm();
    form.setToken(token);
    model.addAttribute("form", form);
    return FORM_VIEW;
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String resetPassword(
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
      useCase.handle(
          new ConfirmPlatformAccountPasswordResetCommand(form.getToken(), form.getNewPassword()));
    } catch (final InvalidVerificationTokenException _) {
      return "identity/platform/verification-link-invalid";
    } catch (final WeakPasswordException _) {
      bindingResult.rejectValue(
          "newPassword", "newPassword.tooWeak", "Password does not meet the minimum requirements");
      return FORM_VIEW;
    }

    return "redirect:/platform/reset-password/success";
  }

  @GetMapping("/success")
  public String success() {
    return "identity/platform/reset-password-success";
  }
}
