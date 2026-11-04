package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.resettotpforaccount.ResetTotpForAccountCommand;
import com.clavaris.identity.application.usecase.resettotpforaccount.ResetTotpForAccountUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * ADR-0040: the dashboard's way to remove an Account's authenticator app, for a person who lost
 * both the app and the recovery codes. It is the same use case the admin API calls ({@code
 * :reset-totp}), so the audit entry, the end of any pause on the Account's codes and the quiet
 * no-op for an Account that has none are written once, not twice. The Account must belong to an
 * Organization the operator owns, as on every account action of this dashboard.
 */
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/users/{accountId}")
public class PlatformAccountTotpResetController {

  private final GetAccountForOrganizationUseCase getAccount;
  private final ResetTotpForAccountUseCase resetTotp;
  private final PlatformAccountOrganizationAccess organizationAccess;

  public PlatformAccountTotpResetController(
      final GetAccountForOrganizationUseCase getAccount,
      final ResetTotpForAccountUseCase resetTotp,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getAccount = getAccount;
    this.resetTotp = resetTotp;
    this.organizationAccess =
        new PlatformAccountOrganizationAccess(organizationResolver, currentPlatformAccount);
  }

  @PostMapping("/totp/reset")
  public String reset(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID accountId) {
    final PlatformAccountOrganizationAccess.ResolvedAccountAccess access =
        organizationAccess.requireOwnedAccount(request, organizationId, accountId, getAccount);
    resetTotp.handle(
        new ResetTotpForAccountCommand(
            access.account().id(),
            AuditActor.platformAccount(access.ownerPlatformAccountId().value())));
    return "redirect:/platform/dashboard/organizations/" + organizationId + "/users/" + accountId;
  }
}
