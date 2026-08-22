package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.requestpasswordreset.RequestPasswordResetCommand;
import com.clavaris.identity.application.usecase.requestpasswordreset.RequestPasswordResetUseCase;
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
 * The "forgot your password?" entry point — {@code organizationId} from the path, never a form
 * field, same BR-ORG-02 rationale as every other controller in this package.
 *
 * <p>Always redirects to the same pending page on a valid submission, whether or not the email
 * resolves to a real account — {@link RequestPasswordResetUseCase}'s own Javadoc explains why a
 * caller-observable difference here would be a user-enumeration oracle.
 */
@Controller
@RequestMapping("/o/{organizationId}/forgot-password")
public class ForgotPasswordController {

  private static final String FORM_VIEW = "identity/forgot-password";

  private final RequestPasswordResetUseCase useCase;

  public ForgotPasswordController(final RequestPasswordResetUseCase useCase) {
    this.useCase = useCase;
  }

  @GetMapping
  public String showForm(@PathVariable final UUID organizationId, final Model model) {
    model.addAttribute("form", new RequestPasswordResetForm());
    return FORM_VIEW;
  }

  // Same "validation failure needs its own early exit" rationale as RegisterAccountController's
  // own suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String requestReset(
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute("form") final RequestPasswordResetForm form,
      final BindingResult bindingResult) {
    if (bindingResult.hasErrors()) {
      return FORM_VIEW;
    }

    useCase.handle(
        new RequestPasswordResetCommand(
            new OrganizationId(organizationId), new Email(form.getEmail())));

    return "redirect:/o/" + organizationId + "/forgot-password/pending";
  }

  @GetMapping("/pending")
  public String pending() {
    return "identity/forgot-password-pending";
  }
}
