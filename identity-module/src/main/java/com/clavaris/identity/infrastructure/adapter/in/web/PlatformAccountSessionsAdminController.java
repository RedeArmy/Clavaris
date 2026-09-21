package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.revokeaccountsession.RevokeAccountSessionCommand;
import com.clavaris.identity.application.usecase.revokeaccountsession.RevokeAccountSessionUseCase;
import com.clavaris.identity.application.usecase.revokeaccountsession.SessionNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * SDE-III review, 2026-09-21 — Clerk dashboard "View Profile" > Profile tab "Devices" parity:
 * operator-driven revoke of a tenant Account's own live session. Reuses {@link
 * RevokeAccountSessionUseCase} (self-service's own use case, ADR-0026) rather than duplicating it —
 * see that command's own Javadoc for why it now carries an {@code actor} field instead of
 * hardcoding one. Same {@link PlatformAccountOrganizationAccess} organization-ownership prologue
 * every sibling {@code .../users/{accountId}/**} controller already shares.
 */
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/users/{accountId}")
public class PlatformAccountSessionsAdminController {

  private static final String REDIRECT_TO_PROFILE = "redirect:/platform/dashboard/organizations/";

  private final GetAccountForOrganizationUseCase getAccount;
  private final RevokeAccountSessionUseCase revokeSession;
  private final PlatformAccountOrganizationAccess organizationAccess;

  public PlatformAccountSessionsAdminController(
      final GetAccountForOrganizationUseCase getAccount,
      final RevokeAccountSessionUseCase revokeSession,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getAccount = getAccount;
    this.revokeSession = revokeSession;
    this.organizationAccess =
        new PlatformAccountOrganizationAccess(organizationResolver, currentPlatformAccount);
  }

  // Deliberately empty — same benign-race rationale as AccountSessionsController's own identical
  // suppression.
  @SuppressWarnings("PMD.EmptyCatchBlock")
  @PostMapping("/sessions/{sessionId}/revoke")
  public String revoke(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID accountId,
      @PathVariable final String sessionId) {
    final PlatformAccountOrganizationAccess.ResolvedAccountAccess access =
        organizationAccess.requireOwnedAccount(request, organizationId, accountId, getAccount);
    try {
      revokeSession.handle(
          new RevokeAccountSessionCommand(
              access.account().id(),
              sessionId,
              AuditActor.platformAccount(access.ownerPlatformAccountId().value())));
    } catch (final SessionNotFoundException _) {
      // Benign race — same reasoning as AccountSessionsController's own identical catch block.
    }
    return REDIRECT_TO_PROFILE + organizationId + "/users/" + accountId;
  }
}
