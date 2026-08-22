package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.requestplatformaccountpasswordreset.RequestPlatformAccountPasswordResetCommand;
import com.clavaris.identity.application.usecase.requestplatformaccountpasswordreset.RequestPlatformAccountPasswordResetUseCase;
import com.clavaris.identity.domain.model.Email;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** Mirrors {@link ForgotPasswordController}, no {@code organizationId} in the path. */
@Controller
@RequestMapping("/platform/forgot-password")
public class ForgotPlatformAccountPasswordController {

  private static final String FORM_VIEW = "identity/platform/forgot-password";

  private final RequestPlatformAccountPasswordResetUseCase useCase;

  public ForgotPlatformAccountPasswordController(
      final RequestPlatformAccountPasswordResetUseCase useCase) {
    this.useCase = useCase;
  }

  @GetMapping
  public String showForm(final Model model) {
    model.addAttribute("form", new RequestPasswordResetForm());
    return FORM_VIEW;
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String requestReset(
      @Valid @ModelAttribute("form") final RequestPasswordResetForm form,
      final BindingResult bindingResult) {
    if (bindingResult.hasErrors()) {
      return FORM_VIEW;
    }

    useCase.handle(new RequestPlatformAccountPasswordResetCommand(new Email(form.getEmail())));

    return "redirect:/platform/forgot-password/pending";
  }

  @GetMapping("/pending")
  public String pending() {
    return "identity/platform/forgot-password-pending";
  }
}
