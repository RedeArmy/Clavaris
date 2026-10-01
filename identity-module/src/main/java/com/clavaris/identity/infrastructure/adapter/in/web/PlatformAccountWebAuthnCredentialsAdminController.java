package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.deletewebauthncredential.DeleteWebAuthnCredentialCommand;
import com.clavaris.identity.application.usecase.deletewebauthncredential.DeleteWebAuthnCredentialUseCase;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * TD-FUT-034, Clerk "View Profile" passkeys parity — operator-driven delete of a tenant Account's
 * own passkey. Reuses {@link DeleteWebAuthnCredentialUseCase} (self-service's own use case, same
 * ownership-scoped delete) rather than duplicating it — same shape {@link
 * PlatformAccountSessionsAdminController} already establishes for session revocation.
 */
// PMD.LongVariable: REDIRECT_TO_PROFILE/organizationAccess/organizationResolver/
// currentPlatformAccount name exactly what they hold — same precedent
// PlatformAccountSessionsAdminController's own identical suppression already establishes.
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/users/{accountId}")
public class PlatformAccountWebAuthnCredentialsAdminController {

  private static final String REDIRECT_TO_PROFILE = "redirect:/platform/dashboard/organizations/";

  private final GetAccountForOrganizationUseCase getAccount;
  private final DeleteWebAuthnCredentialUseCase deleteCredential;
  private final PlatformAccountOrganizationAccess organizationAccess;

  public PlatformAccountWebAuthnCredentialsAdminController(
      final GetAccountForOrganizationUseCase getAccount,
      final DeleteWebAuthnCredentialUseCase deleteCredential,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getAccount = getAccount;
    this.deleteCredential = deleteCredential;
    this.organizationAccess =
        new PlatformAccountOrganizationAccess(organizationResolver, currentPlatformAccount);
  }

  @PostMapping("/passkeys/{credentialId}/delete")
  public String delete(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID accountId,
      @PathVariable final UUID credentialId) {
    final PlatformAccountOrganizationAccess.ResolvedAccountAccess access =
        organizationAccess.requireOwnedAccount(request, organizationId, accountId, getAccount);
    deleteCredential.handle(
        new DeleteWebAuthnCredentialCommand(
            credentialId,
            access.account().id(),
            AuditActor.platformAccount(access.ownerPlatformAccountId().value())));
    return REDIRECT_TO_PROFILE + organizationId + "/users/" + accountId;
  }
}
