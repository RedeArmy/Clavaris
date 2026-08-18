package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.registeraccount.EmailAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.registeraccount.RegisterAccountCommand;
import com.clavaris.identity.application.usecase.registeraccount.RegisterAccountUseCase;
import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
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

/**
 * Thymeleaf form-POST controller (CLAUDE.md §3 — hosted UI, server-rendered), not a JSON API:
 * registration happens through the login/consent surface, not the management API.
 *
 * <p>{@code organizationId} comes from the path, never from a form field — the hosted UI's own
 * origin is scoped per-Organization ({@code {clavarisBaseUrl}/o/{organizationId}/...},
 * integration-design.md §1, ADR-0010 §5.1), the same way {@code /oauth2/authorize} and every other
 * endpoint under this Organization's issuer are. A user filling in an organization ID themselves
 * would be exactly the kind of tenant-boundary mistake ADR-0010 exists to make structurally
 * impossible.
 */
@Controller
@RequestMapping("/o/{organizationId}/register")
public class RegisterAccountController {

  private static final String FORM_VIEW = "identity/register";

  private final RegisterAccountUseCase useCase;

  public RegisterAccountController(final RegisterAccountUseCase useCase) {
    this.useCase = useCase;
  }

  @GetMapping
  public String showForm(@PathVariable final UUID organizationId, final Model model) {
    model.addAttribute("form", new RegisterAccountForm());
    return FORM_VIEW;
  }

  // Early return per rejection reason is clearer here than accumulating a single exit through
  // nested branching for three independent failure modes (validation, taken email, weak
  // password) that each need their own field error — PMD.OnlyOneReturn would make this harder to
  // follow, not easier.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String register(
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute("form") final RegisterAccountForm form,
      final BindingResult bindingResult) {
    if (bindingResult.hasErrors()) {
      return FORM_VIEW;
    }

    // Cross-field check, not expressible as a single-field Bean Validation annotation — a typo
    // in either field must be caught before a raw password is hashed and persisted from a value
    // the user didn't actually mean to set.
    if (!form.getPassword().equals(form.getConfirmPassword())) {
      bindingResult.rejectValue(
          "confirmPassword", "confirmPassword.mismatch", "Passwords do not match");
      return FORM_VIEW;
    }

    try {
      useCase.handle(
          new RegisterAccountCommand(
              new OrganizationId(organizationId), new Email(form.getEmail()), form.getPassword()));
    } catch (EmailAlreadyRegisteredException e) {
      // Never leaks the low-level exception message (which includes the raw organizationId
      // UUID) to the rendered page — a generic, field-scoped error only.
      bindingResult.rejectValue(
          "email", "email.alreadyRegistered", "This email is already registered");
      return FORM_VIEW;
    } catch (WeakPasswordException e) {
      bindingResult.rejectValue(
          "password", "password.tooWeak", "Password does not meet the minimum requirements");
      return FORM_VIEW;
    }

    return "redirect:/o/" + organizationId + "/register/pending-verification";
  }

  @GetMapping("/pending-verification")
  public String pendingVerification() {
    return "identity/register-pending-verification";
  }
}
