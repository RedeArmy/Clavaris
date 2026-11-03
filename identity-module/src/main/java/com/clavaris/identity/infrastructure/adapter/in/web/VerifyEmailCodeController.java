package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.codelockout.CodeTemporarilyLockedException;
import com.clavaris.identity.application.usecase.confirmemailverificationcode.ConfirmEmailVerificationCodeCommand;
import com.clavaris.identity.application.usecase.confirmemailverificationcode.ConfirmEmailVerificationCodeUseCase;
import com.clavaris.identity.application.usecase.confirmemailverificationcode.InvalidVerificationCodeException;
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
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The page an Organization whose email verification method is {@code CODE} or {@code BOTH} sends a
 * person to, to type the code they were mailed (ADR-0024 §2). The address is asked for as well,
 * because the code is only meaningful for one account (see {@code
 * ConfirmEmailVerificationCodeService}). {@code organizationId} comes from the path, never a form
 * field (BR-ORG-02).
 */
@Controller
@RequestMapping("/o/{organizationId}/verify-email/code")
public class VerifyEmailCodeController {

  private static final String FORM_VIEW = "identity/verify-email-code";

  private final ConfirmEmailVerificationCodeUseCase useCase;

  public VerifyEmailCodeController(final ConfirmEmailVerificationCodeUseCase useCase) {
    this.useCase = useCase;
  }

  // The address is only ever prefilled for the person's convenience (carried from the sign-up
  // confirmation page or the email); it is not trusted for anything.
  @GetMapping
  public String showForm(
      @PathVariable final UUID organizationId,
      @RequestParam(required = false) final String email,
      final Model model) {
    final VerifyEmailCodeForm form = new VerifyEmailCodeForm();
    form.setEmail(email);
    model.addAttribute("form", form);
    return FORM_VIEW;
  }

  // Three genuinely distinct exits (validation error, wrong or paused, success) — same rationale as
  // EmailCodeSignInController's own identical suppression.
  @PostMapping
  public String verify(
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute("form") final VerifyEmailCodeForm form,
      final BindingResult bindingResult,
      final Model model) {
    if (bindingResult.hasErrors()) {
      return FORM_VIEW;
    }
    try {
      useCase.handle(
          new ConfirmEmailVerificationCodeCommand(
              new OrganizationId(organizationId), new Email(form.getEmail()), form.getCode()));
    } catch (final InvalidVerificationCodeException _) {
      model.addAttribute("codeError", true);
      return FORM_VIEW;
    } catch (final CodeTemporarilyLockedException _) {
      // ADR-0033: raised for any address alike, so it says nothing about whether an account exists.
      model.addAttribute("codeLocked", true);
      return FORM_VIEW;
    }
    return "identity/verify-email-success";
  }
}
