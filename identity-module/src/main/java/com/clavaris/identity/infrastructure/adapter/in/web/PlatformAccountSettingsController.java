package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.updateaccountpermissions.UpdateAccountPermissionsCommand;
import com.clavaris.identity.application.usecase.updateaccountpermissions.UpdateAccountPermissionsUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * SDE-III review, 2026-09-21 — Clerk dashboard "View Profile" > Settings tab parity ("User
 * permissions"). Same {@link PlatformAccountOrganizationAccess} prologue every sibling {@code
 * .../users/{accountId}/**} controller already shares. A checkbox HTML form submits its own
 * unchecked boxes as simply absent, not {@code "false"} — {@code @RequestParam(defaultValue =
 * "false")} on each boolean here is what turns an absent checkbox into the correct {@code false},
 * not a bug workaround.
 */
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/users/{accountId}")
public class PlatformAccountSettingsController {

  private static final String REDIRECT_TO_PROFILE = "redirect:/platform/dashboard/organizations/";

  private final GetAccountForOrganizationUseCase getAccount;
  private final UpdateAccountPermissionsUseCase updatePermissions;
  private final PlatformAccountOrganizationAccess organizationAccess;

  public PlatformAccountSettingsController(
      final GetAccountForOrganizationUseCase getAccount,
      final UpdateAccountPermissionsUseCase updatePermissions,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getAccount = getAccount;
    this.updatePermissions = updatePermissions;
    this.organizationAccess =
        new PlatformAccountOrganizationAccess(organizationResolver, currentPlatformAccount);
  }

  @PostMapping("/settings")
  public String updateSettings(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID accountId,
      @RequestParam(defaultValue = "false") final boolean canDeleteOwnAccount,
      @RequestParam(defaultValue = "false") final boolean bypassesDeviceTrust) {
    final PlatformAccountOrganizationAccess.ResolvedAccountAccess access =
        organizationAccess.requireOwnedAccount(request, organizationId, accountId, getAccount);
    updatePermissions.handle(
        new UpdateAccountPermissionsCommand(
            access.account().id(),
            canDeleteOwnAccount,
            bypassesDeviceTrust,
            AuditActor.platformAccount(access.ownerPlatformAccountId().value())));
    return REDIRECT_TO_PROFILE + organizationId + "/users/" + accountId + "?settingsUpdated";
  }
}
