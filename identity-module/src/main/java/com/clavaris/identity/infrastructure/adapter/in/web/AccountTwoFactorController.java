package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.codelockout.CodeTemporarilyLockedException;
import com.clavaris.identity.application.usecase.confirmtotpenrollment.ConfirmTotpEnrollmentCommand;
import com.clavaris.identity.application.usecase.confirmtotpenrollment.ConfirmTotpEnrollmentUseCase;
import com.clavaris.identity.application.usecase.disabletotp.DisableTotpCommand;
import com.clavaris.identity.application.usecase.disabletotp.DisableTotpUseCase;
import com.clavaris.identity.application.usecase.gettotpstatus.GetTotpStatusUseCase;
import com.clavaris.identity.application.usecase.regeneraterecoverycodes.RegenerateRecoveryCodesCommand;
import com.clavaris.identity.application.usecase.regeneraterecoverycodes.RegenerateRecoveryCodesUseCase;
import com.clavaris.identity.application.usecase.starttotpenrollment.StartTotpEnrollmentCommand;
import com.clavaris.identity.application.usecase.starttotpenrollment.StartTotpEnrollmentUseCase;
import com.clavaris.identity.application.usecase.starttotpenrollment.TotpAlreadyEnrolledException;
import com.clavaris.identity.application.usecase.starttotpenrollment.TotpEnrollmentStart;
import com.clavaris.identity.application.usecase.starttotpenrollment.TotpNotEnabledException;
import com.clavaris.identity.application.usecase.verifytotpchallenge.InvalidTotpCodeException;
import com.clavaris.identity.domain.model.AccountId;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * ADR-0040: the self-service page where an Account adds, checks and removes its authenticator app
 * and gets its recovery codes. {@code organizationId} is cosmetic and the Account comes from the
 * session, same BR-ORG-02 rationale as {@link AccountWebAuthnCredentialsController}.
 *
 * <p>Pages that show a secret or recovery codes are the answer of a POST, never a GET, and are not
 * stored in the session: a reload or the back button cannot show them again. Anything that removes
 * protection (turning the app off, making new recovery codes) asks for a current code and, like
 * every sensitive account action, for a recently confirmed sign-in.
 */
@Controller
@RequestMapping("/o/{organizationId}/account/two-factor")
public class AccountTwoFactorController {

  private static final String VIEW = "identity/account/two-factor";
  private static final String SETUP_VIEW = "identity/account/two-factor-setup";
  private static final String CODES_VIEW = "identity/account/two-factor-recovery-codes";
  private static final String ISSUER = "Clavaris";
  private static final String ORGANIZATION_ID_ATTRIBUTE = "organizationId";
  private static final String CODE_ERROR_ATTRIBUTE = "codeError";

  private final GetTotpStatusUseCase status;
  private final StartTotpEnrollmentUseCase startEnrollment;
  private final ConfirmTotpEnrollmentUseCase confirmEnrollment;
  private final DisableTotpUseCase disable;
  private final RegenerateRecoveryCodesUseCase regenerate;
  private final CurrentAccountResolver currentAccount;
  private final RequireRecentAuthentication requireRecentAuthentication;

  public AccountTwoFactorController(
      final GetTotpStatusUseCase status,
      final StartTotpEnrollmentUseCase startEnrollment,
      final ConfirmTotpEnrollmentUseCase confirmEnrollment,
      final DisableTotpUseCase disable,
      final RegenerateRecoveryCodesUseCase regenerate,
      final CurrentAccountResolver currentAccount,
      final RequireRecentAuthentication requireRecentAuthentication) {
    this.status = status;
    this.startEnrollment = startEnrollment;
    this.confirmEnrollment = confirmEnrollment;
    this.disable = disable;
    this.regenerate = regenerate;
    this.currentAccount = currentAccount;
    this.requireRecentAuthentication = requireRecentAuthentication;
  }

  @GetMapping
  public String show(
      @PathVariable final UUID organizationId,
      final HttpServletRequest request,
      final Model model) {
    return page(organizationId, requireCurrentAccount(request), model);
  }

  @PostMapping("/setup")
  public String setup(
      @PathVariable final UUID organizationId,
      final HttpServletRequest request,
      final Model model) {
    final AccountId accountId = requireCurrentAccount(request);
    if (requireRecentAuthentication.isStale(organizationId)) {
      return reverify(organizationId);
    }
    return startSetup(organizationId, accountId, model);
  }

  @PostMapping("/confirm")
  public String confirm(
      @PathVariable final UUID organizationId,
      @RequestParam final String code,
      final HttpServletRequest request,
      final Model model) {
    final AccountId accountId = requireCurrentAccount(request);
    if (requireRecentAuthentication.isStale(organizationId)) {
      return reverify(organizationId);
    }
    try {
      final List<String> codes =
          confirmEnrollment.handle(new ConfirmTotpEnrollmentCommand(accountId, code));
      return codes(model, organizationId, codes);
    } catch (final InvalidTotpCodeException _) {
      // The secret is still pending on the server; the person is shown it again to retry.
      model.addAttribute(CODE_ERROR_ATTRIBUTE, true);
      return startSetup(organizationId, accountId, model);
    }
  }

  @PostMapping("/recovery-codes")
  public String regenerateCodes(
      @PathVariable final UUID organizationId,
      @RequestParam final String code,
      final HttpServletRequest request,
      final Model model) {
    final AccountId accountId = requireCurrentAccount(request);
    if (requireRecentAuthentication.isStale(organizationId)) {
      return reverify(organizationId);
    }
    try {
      return codes(
          model,
          organizationId,
          regenerate.handle(new RegenerateRecoveryCodesCommand(accountId, code)));
    } catch (final InvalidTotpCodeException _) {
      model.addAttribute(CODE_ERROR_ATTRIBUTE, true);
      return page(organizationId, accountId, model);
    } catch (final CodeTemporarilyLockedException _) {
      model.addAttribute("codeLocked", true);
      return page(organizationId, accountId, model);
    }
  }

  @PostMapping("/disable")
  public String disableApp(
      @PathVariable final UUID organizationId,
      @RequestParam final String code,
      final HttpServletRequest request,
      final Model model) {
    final AccountId accountId = requireCurrentAccount(request);
    if (requireRecentAuthentication.isStale(organizationId)) {
      return reverify(organizationId);
    }
    try {
      disable.handle(new DisableTotpCommand(accountId, code));
    } catch (final InvalidTotpCodeException _) {
      model.addAttribute(CODE_ERROR_ATTRIBUTE, true);
      return page(organizationId, accountId, model);
    } catch (final CodeTemporarilyLockedException _) {
      model.addAttribute("codeLocked", true);
      return page(organizationId, accountId, model);
    }
    return "redirect:/o/" + organizationId + "/account/two-factor";
  }

  // Also the answer to a wrong confirming code: a new secret replaces the pending one, so a secret
  // that was already shown is not reusable for another try. The cost is one more scan after a typo.
  private String startSetup(
      final UUID organizationId, final AccountId accountId, final Model model) {
    try {
      final TotpEnrollmentStart start =
          startEnrollment.handle(new StartTotpEnrollmentCommand(accountId, ISSUER));
      model.addAttribute("secret", start.secret());
      model.addAttribute("otpauthUri", start.otpauthUri());
      model.addAttribute(ORGANIZATION_ID_ATTRIBUTE, organizationId);
      return SETUP_VIEW;
    } catch (final TotpNotEnabledException | TotpAlreadyEnrolledException _) {
      return page(organizationId, accountId, model);
    }
  }

  private String page(final UUID organizationId, final AccountId accountId, final Model model) {
    final GetTotpStatusUseCase.TotpStatus current = status.handle(accountId);
    model.addAttribute(ORGANIZATION_ID_ATTRIBUTE, organizationId);
    model.addAttribute("available", current.availableToOrganization());
    model.addAttribute("enrolled", current.enrolled());
    model.addAttribute("unusedRecoveryCodes", current.unusedRecoveryCodes());
    return VIEW;
  }

  private static String codes(
      final Model model, final UUID organizationId, final List<String> recoveryCodes) {
    model.addAttribute(ORGANIZATION_ID_ATTRIBUTE, organizationId);
    model.addAttribute("recoveryCodes", recoveryCodes);
    return CODES_VIEW;
  }

  private static String reverify(final UUID organizationId) {
    return "redirect:/o/" + organizationId + "/login";
  }

  private AccountId requireCurrentAccount(final HttpServletRequest request) {
    return CurrentSessionSupport.requireResolved(currentAccount.resolve(request), "Account");
  }
}
