package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.authenticatewithpassword.VerificationOverloadedException;
import com.clavaris.identity.application.usecase.changepassword.ChangePasswordCommand;
import com.clavaris.identity.application.usecase.changepassword.ChangePasswordUseCase;
import com.clavaris.identity.application.usecase.changepassword.IncorrectCurrentPasswordException;
import com.clavaris.identity.application.usecase.changepassword.NoPasswordToChangeException;
import com.clavaris.identity.application.usecase.registeraccount.BreachedPasswordException;
import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.service.PasswordPolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
 * TD-FUT-043: the self-service "change password" page, reached from the Update profile page. Same
 * shape and security posture as {@link AccountProfileController} (its Javadoc applies verbatim):
 * the {@code organizationId} in the path is cosmetic, every action is scoped by the {@link
 * AccountId} of the signed-in session, and {@code app}'s {@code /o/*&#47;account/**} rule already
 * requires {@code ROLE_ACCOUNT}.
 *
 * <p>The current password is the proof of who is asking. A wrong one is shown as a field error and
 * is rate-limited per Account like a sign-in, since this form is also a password oracle for anyone
 * holding a session. A saturated Argon2 gate is a 503 and never a "wrong password" (TD-FUT-017).
 */
@Controller
@RequestMapping("/o/{organizationId}/account/password")
public class AccountPasswordController {

  private static final String FORM_VIEW = "identity/account/password";
  private static final String FORM_ATTRIBUTE = "changePasswordForm";

  private final ChangePasswordUseCase changePassword;
  private final CurrentAccountResolver currentAccount;

  public AccountPasswordController(
      final ChangePasswordUseCase changePassword, final CurrentAccountResolver currentAccount) {
    this.changePassword = changePassword;
    this.currentAccount = currentAccount;
  }

  @GetMapping
  public String show(@PathVariable final UUID organizationId, final Model model) {
    model.addAttribute("organizationId", organizationId);
    model.addAttribute(FORM_ATTRIBUTE, new ChangePasswordForm());
    return FORM_VIEW;
  }

  @PostMapping
  public String change(
      @PathVariable final UUID organizationId,
      final HttpServletRequest request,
      final HttpServletResponse response,
      @Valid @ModelAttribute(FORM_ATTRIBUTE) final ChangePasswordForm form,
      final BindingResult bindingResult,
      final Model model) {
    model.addAttribute("organizationId", organizationId);
    if (bindingResult.hasErrors()) {
      return FORM_VIEW;
    }
    if (!form.getNewPassword().equals(form.getConfirmPassword())) {
      bindingResult.rejectValue(
          "confirmPassword", "confirmPassword.mismatch", "Passwords do not match");
      return FORM_VIEW;
    }
    final AccountId accountId =
        CurrentSessionSupport.requireResolved(currentAccount.resolve(request), "Account");
    try {
      changePassword.handle(
          new ChangePasswordCommand(
              accountId,
              form.getCurrentPassword(),
              form.getNewPassword(),
              CurrentSessionSupport.currentSessionId(request),
              AuditActor.account(accountId.value())));
    } catch (final IncorrectCurrentPasswordException
        | NoPasswordToChangeException
        | VerificationOverloadedException failure) {
      CurrentPasswordFailures.apply(failure, bindingResult, model, response);
      return FORM_VIEW;
    } catch (final WeakPasswordException _) {
      bindingResult.rejectValue(
          "newPassword",
          "newPassword.tooWeak",
          "Password must be between "
              + PasswordPolicy.MIN_LENGTH
              + " and "
              + PasswordPolicy.MAX_LENGTH
              + " characters");
      return FORM_VIEW;
    } catch (final BreachedPasswordException _) {
      // BR-ID-07: generic wording only, never mentioning a breach or its source.
      bindingResult.rejectValue(
          "newPassword",
          "newPassword.breached",
          "This password cannot be used — please choose a different one");
      return FORM_VIEW;
    }
    return "redirect:/o/" + organizationId + "/account/profile?passwordChanged";
  }
}
