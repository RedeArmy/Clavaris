package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.confirmpendingplatformsociallink.ConfirmPendingPlatformSocialLinkCommand;
import com.clavaris.identity.application.usecase.confirmpendingplatformsociallink.ConfirmPendingPlatformSocialLinkUseCase;
import com.clavaris.identity.application.usecase.confirmpendingplatformsociallink.InvalidPendingPlatformSocialLinkException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * {@link ConfirmSocialLinkController}'s platform-tier sibling — the link {@code
 * ResendMailSender.sendPlatformSocialLinkConfirmation} sends, no {@code organizationId} segment
 * (same "no Organization to scope by" shape as every other platform-tier controller in this
 * package).
 */
@Controller
@RequestMapping("/platform/confirm-social-link")
public class ConfirmPlatformSocialLinkController {

  private final ConfirmPendingPlatformSocialLinkUseCase useCase;

  public ConfirmPlatformSocialLinkController(
      final ConfirmPendingPlatformSocialLinkUseCase useCase) {
    this.useCase = useCase;
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @GetMapping
  public String confirm(@RequestParam final String token) {
    try {
      useCase.handle(new ConfirmPendingPlatformSocialLinkCommand(token));
    } catch (final InvalidPendingPlatformSocialLinkException _) {
      return "identity/platform/social-link-invalid";
    }
    return "identity/platform/social-link-confirmed";
  }
}
