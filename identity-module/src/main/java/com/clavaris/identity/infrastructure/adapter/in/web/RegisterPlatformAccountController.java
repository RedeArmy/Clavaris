package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountEmailAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.registerplatformaccount.RegisterPlatformAccountCommand;
import com.clavaris.identity.application.usecase.registerplatformaccount.RegisterPlatformAccountUseCase;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.RequestPlatformAccountEmailVerificationCommand;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.RequestPlatformAccountEmailVerificationUseCase;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccountId;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * ADR-0012: the self-service signup entry point for a {@code PlatformAccount} — no {@code
 * organizationId} in the path, unlike {@link RegisterAccountController}: a platform account belongs
 * to no Organization, it goes on to own zero or more.
 */
@Controller
@RequestMapping("/platform/register")
public class RegisterPlatformAccountController {

  private static final String FORM_VIEW = "identity/platform/register";

  private final RegisterPlatformAccountUseCase useCase;

  @SuppressWarnings("PMD.LongVariable")
  private final RequestPlatformAccountEmailVerificationUseCase requestEmailVerification;

  public RegisterPlatformAccountController(
      final RegisterPlatformAccountUseCase useCase,
      @SuppressWarnings("PMD.LongVariable")
          final RequestPlatformAccountEmailVerificationUseCase requestEmailVerification) {
    this.useCase = useCase;
    this.requestEmailVerification = requestEmailVerification;
  }

  @GetMapping
  public String showForm(final Model model) {
    model.addAttribute("form", new RegisterPlatformAccountForm());
    return FORM_VIEW;
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String register(
      @Valid @ModelAttribute("form") final RegisterPlatformAccountForm form,
      final BindingResult bindingResult) {
    if (bindingResult.hasErrors()) {
      return FORM_VIEW;
    }

    if (!form.getPassword().equals(form.getConfirmPassword())) {
      bindingResult.rejectValue(
          "confirmPassword", "confirmPassword.mismatch", "Passwords do not match");
      return FORM_VIEW;
    }

    final PlatformAccountId accountId;
    try {
      accountId =
          useCase.handle(
              new RegisterPlatformAccountCommand(new Email(form.getEmail()), form.getPassword()));
    } catch (PlatformAccountEmailAlreadyRegisteredException _) {
      bindingResult.rejectValue(
          "email", "email.alreadyRegistered", "This email is already registered");
      return FORM_VIEW;
    } catch (WeakPasswordException _) {
      bindingResult.rejectValue(
          "password", "password.tooWeak", "Password does not meet the minimum requirements");
      return FORM_VIEW;
    }

    requestEmailVerification.handle(new RequestPlatformAccountEmailVerificationCommand(accountId));

    return "redirect:/platform/register/pending-verification";
  }

  @GetMapping("/pending-verification")
  public String pendingVerification() {
    return "identity/platform/register-pending-verification";
  }
}
