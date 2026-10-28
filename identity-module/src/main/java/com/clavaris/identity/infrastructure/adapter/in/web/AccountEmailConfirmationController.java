package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.confirmemailchange.ConfirmEmailChangeCommand;
import com.clavaris.identity.application.usecase.confirmemailchange.ConfirmEmailChangeUseCase;
import com.clavaris.identity.application.usecase.confirmemailchange.InvalidEmailChangeTokenException;
import com.clavaris.identity.application.usecase.registeraccount.EmailAlreadyRegisteredException;
import com.clavaris.identity.domain.model.AccountId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * TD-FUT-043: the page the emailed email-change link lands on, and the click that completes the
 * change. Split from {@link AccountEmailController} (the form that starts it). Behind the Account's
 * session on purpose: the link only works for the Account that requested it, so a forwarded or
 * leaked link is useless to anyone else.
 */
@Controller
@RequestMapping("/o/{organizationId}/account/email/confirm")
public class AccountEmailConfirmationController {

  private static final String RESULT_VIEW = "identity/account/email-confirmed";
  private static final String ORGANIZATION_ATTRIBUTE = "organizationId";

  private final ConfirmEmailChangeUseCase confirmEmailChange;
  private final CurrentAccountResolver currentAccount;

  public AccountEmailConfirmationController(
      final ConfirmEmailChangeUseCase confirmEmailChange,
      final CurrentAccountResolver currentAccount) {
    this.confirmEmailChange = confirmEmailChange;
    this.currentAccount = currentAccount;
  }

  // A GET must not change anything: mail scanners and link previewers open every URL in a message,
  // and would use up the single-use link before its owner did. The link therefore lands on a page
  // that asks for a click, and only the POST below changes the address. It also leaves the token in
  // the address bar for as short a time as possible (Referrer-Policy: no-referrer covers the rest).
  @GetMapping
  public String confirmPage(
      @PathVariable final UUID organizationId,
      @RequestParam final String token,
      final Model model) {
    model.addAttribute(ORGANIZATION_ATTRIBUTE, organizationId);
    model.addAttribute("pendingToken", token);
    return RESULT_VIEW;
  }

  @PostMapping
  public String confirm(
      @PathVariable final UUID organizationId,
      @RequestParam final String token,
      final HttpServletRequest httpRequest,
      final HttpServletResponse response,
      final Model model) {
    model.addAttribute(ORGANIZATION_ATTRIBUTE, organizationId);
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
