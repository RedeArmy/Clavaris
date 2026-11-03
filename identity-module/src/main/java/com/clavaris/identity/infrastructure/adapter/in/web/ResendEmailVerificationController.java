package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicySnapshot;
import com.clavaris.identity.application.usecase.requestemailverification.EmailVerificationMethod;
import com.clavaris.identity.application.usecase.resendemailverification.ResendEmailVerificationCommand;
import com.clavaris.identity.application.usecase.resendemailverification.ResendEmailVerificationUseCase;
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
 * Asking for another verification email (TD-FUT-055, ADR-0038). It is reached from the "check your
 * email" page, from the sign-in that was refused for an unverified address, and from the page that
 * takes the code, each carrying the address along so the field is already filled.
 *
 * <p>The answer never depends on the address: a valid-looking value always redirects to the same
 * "sent" page, whether or not it names an Account that needs another email (the use case decides,
 * on the bounded email pool, after this has answered). {@code organizationId} comes from the path,
 * never a form field (BR-ORG-02).
 */
@Controller
@RequestMapping("/o/{organizationId}/verify-email/resend")
public class ResendEmailVerificationController {

  private static final String FORM_VIEW = "identity/verify-email-resend";
  private static final String SENT_VIEW = "identity/verify-email-resend-sent";
  private static final String EMAIL = "email";
  private static final String USERNAME_SIGN_IN = "usernameSignInEnabled";

  private final ResendEmailVerificationUseCase useCase;
  private final AccountAuthenticationPolicyProvider policyProvider;

  public ResendEmailVerificationController(
      final ResendEmailVerificationUseCase useCase,
      final AccountAuthenticationPolicyProvider policyProvider) {
    this.useCase = useCase;
    this.policyProvider = policyProvider;
  }

  // The address is only ever prefilled for the person's convenience; it is not trusted for
  // anything.
  @GetMapping
  public String showForm(
      @PathVariable final UUID organizationId,
      @RequestParam(required = false) final String email,
      final Model model) {
    final ResendEmailVerificationForm form = new ResendEmailVerificationForm();
    form.setEmail(email);
    model.addAttribute("form", form);
    addIdentifierOptions(organizationId, model);
    return FORM_VIEW;
  }

  @PostMapping
  public String resend(
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute("form") final ResendEmailVerificationForm form,
      final BindingResult bindingResult,
      final Model model) {
    if (bindingResult.hasErrors()) {
      addIdentifierOptions(organizationId, model);
      return FORM_VIEW;
    }
    useCase.handle(
        new ResendEmailVerificationCommand(new OrganizationId(organizationId), form.getEmail()));
    return RedirectQueryParams.appendIfPresent(
        "redirect:/o/" + organizationId + "/verify-email/resend/sent", EMAIL, form.getEmail());
  }

  // The same page for every address. The address only rides along so the link to the code page,
  // which an Organization that mails a code needs, can have it filled in; it is escaped when shown.
  @GetMapping("/sent")
  public String sent(
      @PathVariable final UUID organizationId,
      @RequestParam(required = false) final String email,
      final Model model) {
    final AccountAuthenticationPolicySnapshot policy =
        policyProvider.policyFor(new OrganizationId(organizationId));
    model.addAttribute(EMAIL, email);
    model.addAttribute(
        "codeEntry", policy.emailVerificationMethod() != EmailVerificationMethod.LINK);
    return SENT_VIEW;
  }

  private void addIdentifierOptions(final UUID organizationId, final Model model) {
    model.addAttribute(
        USERNAME_SIGN_IN,
        policyProvider.policyFor(new OrganizationId(organizationId)).usernameSignInEnabled());
  }
}
