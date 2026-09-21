package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.revokealloauthgrantsforaccount.RevokeAllOAuthGrantsForAccountCommand;
import com.clavaris.identity.application.usecase.revokealloauthgrantsforaccount.RevokeAllOAuthGrantsForAccountUseCase;
import com.clavaris.identity.application.usecase.revokeoauthgrant.OAuthGrantNotFoundException;
import com.clavaris.identity.application.usecase.revokeoauthgrant.RevokeOAuthGrantCommand;
import com.clavaris.identity.application.usecase.revokeoauthgrant.RevokeOAuthGrantUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * SDE-III review, 2026-09-21 — Clerk dashboard "View Profile" > OAuth tab parity: per-grant and
 * revoke-all actions against {@code oauth2_authorization} (TD-SEC-003). Same {@link
 * PlatformAccountOrganizationAccess} organization-ownership prologue every sibling {@code
 * .../users/{accountId}/**} controller already shares.
 */
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/users/{accountId}")
public class PlatformAccountOAuthGrantsAdminController {

  private static final String REDIRECT_TO_PROFILE = "redirect:/platform/dashboard/organizations/";

  private final GetAccountForOrganizationUseCase getAccount;
  private final RevokeOAuthGrantUseCase revokeGrant;
  private final RevokeAllOAuthGrantsForAccountUseCase revokeAllGrants;
  private final PlatformAccountOrganizationAccess organizationAccess;

  public PlatformAccountOAuthGrantsAdminController(
      final GetAccountForOrganizationUseCase getAccount,
      final RevokeOAuthGrantUseCase revokeGrant,
      final RevokeAllOAuthGrantsForAccountUseCase revokeAllGrants,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getAccount = getAccount;
    this.revokeGrant = revokeGrant;
    this.revokeAllGrants = revokeAllGrants;
    this.organizationAccess =
        new PlatformAccountOrganizationAccess(organizationResolver, currentPlatformAccount);
  }

  // Deliberately empty — same benign-race rationale as PlatformAccountSessionsAdminController's
  // own identical suppression.
  @SuppressWarnings("PMD.EmptyCatchBlock")
  @PostMapping("/oauth-grants/{authorizationId}/revoke")
  public String revoke(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID accountId,
      @PathVariable final String authorizationId) {
    final PlatformAccountOrganizationAccess.ResolvedAccountAccess access =
        organizationAccess.requireOwnedAccount(request, organizationId, accountId, getAccount);
    try {
      revokeGrant.handle(
          new RevokeOAuthGrantCommand(access.account().id(), authorizationId, actorFor(access)));
    } catch (final OAuthGrantNotFoundException _) {
      // Benign race — same reasoning as PlatformAccountSessionsAdminController's own identical
      // catch block.
    }
    return redirectToProfile(organizationId, accountId);
  }

  @PostMapping("/oauth-grants/revoke-all")
  public String revokeAll(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID accountId) {
    final PlatformAccountOrganizationAccess.ResolvedAccountAccess access =
        organizationAccess.requireOwnedAccount(request, organizationId, accountId, getAccount);
    revokeAllGrants.handle(
        new RevokeAllOAuthGrantsForAccountCommand(access.account().id(), actorFor(access)));
    return redirectToProfile(organizationId, accountId);
  }

  private static AuditActor actorFor(
      final PlatformAccountOrganizationAccess.ResolvedAccountAccess access) {
    return AuditActor.platformAccount(access.ownerPlatformAccountId().value());
  }

  private static String redirectToProfile(final UUID organizationId, final UUID accountId) {
    return REDIRECT_TO_PROFILE + organizationId + "/users/" + accountId;
  }
}
