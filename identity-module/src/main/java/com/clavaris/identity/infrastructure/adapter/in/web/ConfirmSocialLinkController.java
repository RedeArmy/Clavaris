package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.confirmpendingsociallink.ConfirmPendingSocialLinkCommand;
import com.clavaris.identity.application.usecase.confirmpendingsociallink.ConfirmPendingSocialLinkUseCase;
import com.clavaris.identity.application.usecase.confirmpendingsociallink.InvalidPendingSocialLinkException;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The link {@code ResendMailSender.sendSocialLinkConfirmation} sends — same "a plain {@code GET},
 * clicking the link is the whole action" shape as {@link VerifyEmailController}, {@code
 * organizationId} from the path for the same BR-ORG-02 reasoning. Deliberately does not establish a
 * session on success (same as {@link VerifyEmailController}'s own equivalent) — the account holder
 * clicks back through to the login page and signs in with the now-linked provider, which resolves
 * straight to the "identity already linked" branch on the next attempt.
 */
@Controller
@RequestMapping("/o/{organizationId}/confirm-social-link")
public class ConfirmSocialLinkController {

  private final ConfirmPendingSocialLinkUseCase useCase;

  public ConfirmSocialLinkController(final ConfirmPendingSocialLinkUseCase useCase) {
    this.useCase = useCase;
  }

  // Success vs. invalid-token each need their own exit — same rationale as
  // VerifyEmailController's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @GetMapping
  public String confirm(@PathVariable final UUID organizationId, @RequestParam final String token) {
    try {
      useCase.handle(new ConfirmPendingSocialLinkCommand(token));
    } catch (final InvalidPendingSocialLinkException _) {
      return "identity/social-link-invalid";
    }
    return "identity/social-link-confirmed";
  }
}
