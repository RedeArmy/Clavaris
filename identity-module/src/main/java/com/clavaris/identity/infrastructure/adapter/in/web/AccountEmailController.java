package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.authenticatewithpassword.VerificationOverloadedException;
import com.clavaris.identity.application.usecase.changepassword.IncorrectCurrentPasswordException;
import com.clavaris.identity.application.usecase.changepassword.RecentSignInRequiredException;
import com.clavaris.identity.application.usecase.requestemailchange.EmailChangeNotAllowedException;
import com.clavaris.identity.application.usecase.requestemailchange.RequestEmailChangeCommand;
import com.clavaris.identity.application.usecase.requestemailchange.RequestEmailChangeUseCase;
import com.clavaris.identity.application.usecase.requestemailchange.SelfServiceEmailChangePolicy;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * TD-FUT-043: the self-service "change email" page and the page its emailed link lands on. Same
 * posture as {@link AccountPasswordController}: the path's {@code organizationId} is cosmetic and
 * everything is scoped by the signed-in session's Account.
 *
 * <p>Asking is behind the Account's session on purpose — the link only works for the Account that
 * requested it, so a forwarded or leaked link is useless to anyone else.
 */
@Controller
@RequestMapping("/o/{organizationId}/account/email")
public class AccountEmailController {

  private static final String FORM_VIEW = "identity/account/email";
  private static final String FORM_ATTRIBUTE = "changeEmailForm";
  private static final String ORGANIZATION_ATTRIBUTE = "organizationId";

  private final RequestEmailChangeUseCase requestEmailChange;
  private final CurrentAccountResolver currentAccount;
  private final SelfServiceEmailChangePolicy policy;
  private final Duration recentSignInWindow;

  public AccountEmailController(
      final RequestEmailChangeUseCase requestEmailChange,
      final CurrentAccountResolver currentAccount,
      final SelfServiceEmailChangePolicy policy,
      @Value("${clavaris.self-service.recent-sign-in-minutes:10}") final long recentSignInMinutes) {
    this.requestEmailChange = requestEmailChange;
    this.currentAccount = currentAccount;
    this.policy = policy;
    this.recentSignInWindow = Duration.ofMinutes(recentSignInMinutes);
  }

  @GetMapping
  public String show(@PathVariable final UUID organizationId, final Model model) {
    model.addAttribute(ORGANIZATION_ATTRIBUTE, organizationId);
    model.addAttribute(FORM_ATTRIBUTE, new ChangeEmailForm());
    // A hint only: the use case enforces the policy of the Account's own Organization.
    model.addAttribute(
        "emailChangeDisabled", !policy.isAllowed(new OrganizationId(organizationId)));
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
    model.addAttribute(ORGANIZATION_ATTRIBUTE, organizationId);
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
              AuditActor.account(accountId.value()),
              SessionFactors.signedInWithin(
                  SecurityContextHolder.getContext().getAuthentication(), recentSignInWindow)));
    } catch (final EmailChangeNotAllowedException _) {
      model.addAttribute("emailChangeDisabled", true);
      return FORM_VIEW;
    } catch (final IncorrectCurrentPasswordException
        | RecentSignInRequiredException
        | VerificationOverloadedException failure) {
      CurrentPasswordFailures.apply(failure, bindingResult, model, response);
      return FORM_VIEW;
    }
    return "redirect:/o/" + organizationId + "/account/profile?emailChangeRequested";
  }
}
