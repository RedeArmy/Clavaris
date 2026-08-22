package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.confirmemailverification.ConfirmEmailVerificationCommand;
import com.clavaris.identity.application.usecase.confirmemailverification.ConfirmEmailVerificationUseCase;
import com.clavaris.identity.application.usecase.confirmemailverification.InvalidVerificationTokenException;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The link a verification email sends the user to (see {@code ResendMailSender#link}) — a plain
 * {@code GET}, not a form: clicking the link is the whole action, same UX shape every comparable
 * system uses for this flow. {@code organizationId} from the path, never a form field — same
 * BR-ORG-02 rationale as every other controller in this package.
 */
@Controller
@RequestMapping("/o/{organizationId}/verify-email")
public class VerifyEmailController {

  private final ConfirmEmailVerificationUseCase useCase;

  public VerifyEmailController(final ConfirmEmailVerificationUseCase useCase) {
    this.useCase = useCase;
  }

  // Success vs. invalid-token each need their own exit — same rationale as
  // RegisterAccountController's own suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @GetMapping
  public String verify(@PathVariable final UUID organizationId, @RequestParam final String token) {
    try {
      useCase.handle(new ConfirmEmailVerificationCommand(token));
    } catch (final InvalidVerificationTokenException _) {
      return "identity/verification-link-invalid";
    }
    return "identity/verify-email-success";
  }
}
