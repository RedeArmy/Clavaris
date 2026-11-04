package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.getaccountauthenticationpolicyfororganization.GetAccountAuthenticationPolicyForOrganizationUseCase;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountQuery;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization.OrganizationNotFoundException;
import com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization.PasswordOptionalRequiresPasswordlessSignInException;
import com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization.SetAccountAuthenticationPolicyForOrganizationCommand;
import com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization.SetAccountAuthenticationPolicyForOrganizationUseCase;
import com.clavaris.organization.domain.model.AccountAuthenticationPolicy;
import com.clavaris.organization.domain.model.Organization;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * The dashboard's own read and write path for how an Organization's people sign in, today the one
 * switch the operator asked for: the one-time access code (ADR-0037), {@code GET}/{@code POST
 * /platform/dashboard/organizations/{organizationId}/sign-in-options}. The switch is the
 * Organization's {@code emailCodeSignInEnabled}: on, the hosted sign-in leads with the code (only
 * the email or username, then the six boxes) and keeps the password one link away (BR-ID-12).
 *
 * <p>Every write goes through the same {@link SetAccountAuthenticationPolicyForOrganizationUseCase}
 * the REST admin API uses, so the rule that an Organization whose sign-up has no password must keep
 * a passwordless method holds here too. The use case replaces the whole policy, so the command is
 * built from the saved one with only this switch changed: nothing else the operator set through the
 * API is touched. Ownership is checked first, an Organization that is someone else's resolving like
 * one that does not exist, as in every dashboard controller.
 */
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/sign-in-options")
public class PlatformSignInOptionsController {

  private static final String VIEW = "organization/platform/organization-sign-in-options";
  private static final String FRAGMENT = VIEW + " :: signInOptions";
  private static final String HX_REQUEST_HEADER = "HX-Request";

  private final GetOrganizationForPlatformAccountUseCase getOrganization;
  private final GetAccountAuthenticationPolicyForOrganizationUseCase getPolicy;
  private final SetAccountAuthenticationPolicyForOrganizationUseCase setPolicy;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  public PlatformSignInOptionsController(
      final GetOrganizationForPlatformAccountUseCase getOrganization,
      final GetAccountAuthenticationPolicyForOrganizationUseCase getPolicy,
      final SetAccountAuthenticationPolicyForOrganizationUseCase setPolicy,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getOrganization = getOrganization;
    this.getPolicy = getPolicy;
    this.setPolicy = setPolicy;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  @GetMapping
  public String show(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      final Model model) {
    final Organization organization =
        requireOwnedOrganization(organizationId, requireCurrentPlatformAccount(request));
    populate(model, organization, organizationId, null);
    return VIEW;
  }

  // The checkbox is simply absent from the submission when it is off, which reads as false.
  @PostMapping
  public String set(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @RequestParam(name = "accessCodeEnabled", defaultValue = "false")
          final boolean accessCodeEnabled,
      @RequestParam(name = "usernameSignInEnabled", defaultValue = "false")
          final boolean usernameSignInEnabled,
      @RequestParam(name = "totpEnabled", defaultValue = "false") final boolean totpEnabled,
      @RequestParam(name = "passkeysEnabled", defaultValue = "false") final boolean passkeysEnabled,
      @RequestParam(name = "passkeyOfferEnabled", defaultValue = "false")
          final boolean passkeyOfferEnabled,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final Organization organization =
        requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final AccountAuthenticationPolicy saved = getPolicy.handle(organizationId);

    try {
      setPolicy.handle(
          new SetAccountAuthenticationPolicyForOrganizationCommand(
              organizationId,
              saved.emailVerificationRequiredAtSignIn(),
              saved.emailVerificationMethod(),
              accessCodeEnabled,
              saved.emailLinkSignInEnabled(),
              saved.usernameRequired(),
              usernameSignInEnabled,
              saved.passwordAtSignUpEnabled(),
              saved.deviceTrustEnabled(),
              saved.selfRegistrationRequiresApproval(),
              saved.selfServiceEmailChangeEnabled(),
              saved.codeAfterPasswordRequired(),
              totpEnabled,
              passkeysEnabled,
              passkeyOfferEnabled,
              AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final PasswordOptionalRequiresPasswordlessSignInException _) {
      // Turning the code off would leave an Organization whose sign-up has no password with no way
      // to sign in at all; the page says so instead of failing.
      populate(model, organization, organizationId, "passwordOptionalNeedsPasswordless");
      return isHtmxRequest(request) ? FRAGMENT : VIEW;
    } catch (final OrganizationNotFoundException _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    if (isHtmxRequest(request)) {
      populate(model, organization, organizationId, null);
      return FRAGMENT;
    }
    return "redirect:/platform/dashboard/organizations/" + organizationId + "/sign-in-options";
  }

  private void populate(
      final Model model,
      final Organization organization,
      final UUID organizationId,
      final String error) {
    model.addAttribute("organizationId", organizationId);
    model.addAttribute("organizationName", organization.name());
    final AccountAuthenticationPolicy policy = getPolicy.handle(organizationId);
    model.addAttribute("accessCodeEnabled", policy.emailCodeSignInEnabled());
    model.addAttribute("usernameSignInEnabled", policy.usernameSignInEnabled());
    model.addAttribute("totpEnabled", policy.totpEnabled());
    model.addAttribute("passkeysEnabled", policy.passkeysEnabled());
    model.addAttribute("passkeyOfferEnabled", policy.passkeyOfferEnabled());
    model.addAttribute("signInOptionsError", error);
  }

  private Organization requireOwnedOrganization(
      final UUID organizationId, final UUID ownerPlatformAccountId) {
    return getOrganization
        .handle(new GetOrganizationForPlatformAccountQuery(organizationId, ownerPlatformAccountId))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private static boolean isHtmxRequest(final HttpServletRequest request) {
    return "true".equals(request.getHeader(HX_REQUEST_HEADER));
  }

  private UUID requireCurrentPlatformAccount(final HttpServletRequest request) {
    return currentPlatformAccount
        .resolve(request)
        .orElseThrow(
            () -> new IllegalStateException("No authenticated PlatformAccount on this request"));
  }
}
