package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.authenticatewithpassword.VerificationOverloadedException;
import com.clavaris.identity.application.usecase.changepassword.IncorrectCurrentPasswordException;
import com.clavaris.identity.application.usecase.changepassword.NoPasswordToChangeException;
import com.clavaris.identity.application.usecase.confirmemailchange.ConfirmEmailChangeCommand;
import com.clavaris.identity.application.usecase.confirmemailchange.ConfirmEmailChangeUseCase;
import com.clavaris.identity.application.usecase.confirmemailchange.InvalidEmailChangeTokenException;
import com.clavaris.identity.application.usecase.registeraccount.EmailAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.requestemailchange.RequestEmailChangeCommand;
import com.clavaris.identity.application.usecase.requestemailchange.RequestEmailChangeUseCase;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
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
import org.springframework.web.bind.annotation.RequestParam;

/**
 * TD-FUT-043: the self-service "change email" page and the page its emailed link lands on. Same
 * posture as {@link AccountPasswordController}: the path's {@code organizationId} is cosmetic and
 * everything is scoped by the signed-in session's Account.
 *
 * <p>Asking and confirming are both behind the Account's session on purpose — the link only works
 * for the Account that requested it, so a forwarded or leaked link is useless to anyone else.
 */
@Controller
@RequestMapping("/o/{organizationId}/account/email")
public class AccountEmailController {

  private static final String FORM_VIEW = "identity/account/email";
  private static final String RESULT_VIEW = "identity/account/email-confirmed";
  private static final String FORM_ATTRIBUTE = "changeEmailForm";

  private final RequestEmailChangeUseCase requestEmailChange;
  private final ConfirmEmailChangeUseCase confirmEmailChange;
  private final CurrentAccountResolver currentAccount;

  public AccountEmailController(
      final RequestEmailChangeUseCase requestEmailChange,
      final ConfirmEmailChangeUseCase confirmEmailChange,
      final CurrentAccountResolver currentAccount) {
    this.requestEmailChange = requestEmailChange;
    this.confirmEmailChange = confirmEmailChange;
    this.currentAccount = currentAccount;
  }

  @GetMapping
  public String show(@PathVariable final UUID organizationId, final Model model) {
    model.addAttribute("organizationId", organizationId);
    model.addAttribute(FORM_ATTRIBUTE, new ChangeEmailForm());
    return FORM_VIEW;
  }

  @PostMapping
  public String request(
      @PathVariable final UUID organizationId,
      final HttpServletRequest httpRequest,
      final HttpServletResponse response,
      @Valid @ModelAttribute(FORM_ATTRIBUTE) final ChangeEmailForm form,
      final BindingResult bindingResult,
      final Model model) {
    model.addAttribute("organizationId", organizationId);
    if (bindingResult.hasErrors()) {
      return FORM_VIEW;
    }
    final AccountId accountId =
        CurrentSessionSupport.requireResolved(currentAccount.resolve(httpRequest), "Account");
    final Email newEmail;
    try {
      newEmail = new Email(form.getNewEmail());
    } catch (final IllegalArgumentException _) {
      bindingResult.rejectValue("newEmail", "newEmail.invalid", "Enter a valid email address");
      return FORM_VIEW;
    }
    try {
      requestEmailChange.handle(
          new RequestEmailChangeCommand(
              accountId,
              form.getCurrentPassword(),
              newEmail,
              AuditActor.account(accountId.value())));
    } catch (final IncorrectCurrentPasswordException _) {
      bindingResult.rejectValue(
          "currentPassword", "currentPassword.incorrect", "The current password is incorrect");
      return FORM_VIEW;
    } catch (final NoPasswordToChangeException _) {
      model.addAttribute("noPassword", true);
      return FORM_VIEW;
    } catch (final VerificationOverloadedException _) {
      response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      model.addAttribute("serviceOverloadedError", true);
      return FORM_VIEW;
    }
    return "redirect:/o/" + organizationId + "/account/profile?emailChangeRequested";
  }

  @GetMapping("/confirm")
  public String confirm(
      @PathVariable final UUID organizationId,
      @RequestParam final String token,
      final HttpServletRequest httpRequest,
      final HttpServletResponse response,
      final Model model) {
    model.addAttribute("organizationId", organizationId);
    final AccountId accountId =
        CurrentSessionSupport.requireResolved(currentAccount.resolve(httpRequest), "Account");
    try {
      confirmEmailChange.handle(new ConfirmEmailChangeCommand(accountId, token));
      model.addAttribute("confirmed", true);
    } catch (final InvalidEmailChangeTokenException _) {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      model.addAttribute("invalid", true);
    } catch (final EmailAlreadyRegisteredException _) {
      response.setStatus(HttpServletResponse.SC_CONFLICT);
      model.addAttribute("taken", true);
    }
    return RESULT_VIEW;
  }
}
