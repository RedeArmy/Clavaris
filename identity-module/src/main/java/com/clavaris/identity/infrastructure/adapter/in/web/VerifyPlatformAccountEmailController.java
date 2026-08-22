package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.confirmplatformaccountemailverification.ConfirmPlatformAccountEmailVerificationCommand;
import com.clavaris.identity.application.usecase.confirmplatformaccountemailverification.ConfirmPlatformAccountEmailVerificationUseCase;
import com.clavaris.identity.application.usecase.confirmplatformaccountemailverification.InvalidVerificationTokenException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The link a platform-account verification email sends the user to — mirrors {@link
 * VerifyEmailController}, no {@code organizationId} in the path.
 */
@Controller
@RequestMapping("/platform/verify-email")
public class VerifyPlatformAccountEmailController {

  private final ConfirmPlatformAccountEmailVerificationUseCase useCase;

  public VerifyPlatformAccountEmailController(
      final ConfirmPlatformAccountEmailVerificationUseCase useCase) {
    this.useCase = useCase;
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @GetMapping
  public String verify(@RequestParam final String token) {
    try {
      useCase.handle(new ConfirmPlatformAccountEmailVerificationCommand(token));
    } catch (final InvalidVerificationTokenException _) {
      return "identity/platform/verification-link-invalid";
    }
    return "identity/platform/verify-email-success";
  }
}
