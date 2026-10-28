package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.authenticatewithpassword.VerificationOverloadedException;
import com.clavaris.identity.application.usecase.changepassword.IncorrectCurrentPasswordException;
import com.clavaris.identity.application.usecase.changepassword.RecentSignInRequiredException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;

/**
 * TD-FUT-043: how the password change and the email change pages show the three failures they
 * share, because both start by checking the current password: it is wrong, the account has no
 * password, or the Argon2 gate is saturated. One place, so the two pages cannot drift apart on
 * wording or on the status code.
 */
final class CurrentPasswordFailures {

  private CurrentPasswordFailures() {
    // Utility class — never instantiated.
  }

  /** Records {@code failure} on the form, the model or the response; the caller re-renders. */
  /* package */ static void apply(
      final RuntimeException failure,
      final BindingResult bindingResult,
      final Model model,
      final HttpServletResponse response) {
    if (failure instanceof IncorrectCurrentPasswordException) {
      bindingResult.rejectValue(
          "currentPassword", "currentPassword.incorrect", "The current password is incorrect");
    } else if (failure instanceof RecentSignInRequiredException) {
      model.addAttribute("recentSignInRequired", true);
    } else if (failure instanceof VerificationOverloadedException) {
      // The current password was never checked: a 503, not a wrong-password message (TD-FUT-017).
      response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      model.addAttribute("serviceOverloadedError", true);
    } else {
      throw failure;
    }
  }
}
