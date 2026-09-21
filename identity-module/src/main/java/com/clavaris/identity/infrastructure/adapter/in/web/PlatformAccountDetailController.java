package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.SocialIdentityRepository;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.impersonateaccount.OAuthClientsForOrganizationProvider;
import com.clavaris.identity.application.usecase.listactivesessionsforaccount.ActiveAccountSession;
import com.clavaris.identity.application.usecase.listactivesessionsforaccount.ListActiveSessionsForAccountQuery;
import com.clavaris.identity.application.usecase.listactivesessionsforaccount.ListActiveSessionsForAccountUseCase;
import com.clavaris.identity.application.usecase.listoauthgrantsforaccount.ListOAuthGrantsForAccountQuery;
import com.clavaris.identity.application.usecase.listoauthgrantsforaccount.ListOAuthGrantsForAccountUseCase;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.KnownDeviceRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * SDE-III review, 2026-09-19 — Clerk dashboard "Users" tab "View Profile" menu item: a read-only
 * Account detail page (identity fields, known devices, linked social providers). Metadata,
 * biometric/WebAuthn credentials, and an activity heatmap are deliberately out of scope — see
 * TD-FUT-034 (`technical-debt-register.md`).
 *
 * <p>{@code organizationId} resolves through {@link OrganizationForPlatformAccountResolver}, same
 * anti-enumeration posture as {@link PlatformAccountsController}; a mismatched {@code accountId}
 * (wrong Organization, or none at all) 404s identically via {@link
 * GetAccountForOrganizationUseCase}'s own Organization-scoped lookup.
 */
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/users/{accountId}")
public class PlatformAccountDetailController {

  private static final String PROFILE_VIEW = "identity/platform/account-profile";
  private static final String ORGANIZATION_ID_ATTRIBUTE = "organizationId";
  private static final String ORGANIZATION_NAME_ATTRIBUTE = "organizationName";

  private final GetAccountForOrganizationUseCase getAccount;
  private final KnownDeviceRepository knownDevices;
  private final SocialIdentityRepository socialIdentities;
  private final OAuthClientsForOrganizationProvider oauthClientsProvider;
  private final ListActiveSessionsForAccountUseCase listSessions;
  private final ListOAuthGrantsForAccountUseCase listOAuthGrants;
  private final PlatformAccountOrganizationAccess organizationAccess;

  @SuppressWarnings("java:S107")
  public PlatformAccountDetailController(
      final GetAccountForOrganizationUseCase getAccount,
      final KnownDeviceRepository knownDevices,
      final SocialIdentityRepository socialIdentities,
      final OAuthClientsForOrganizationProvider oauthClientsProvider,
      final ListActiveSessionsForAccountUseCase listSessions,
      final ListOAuthGrantsForAccountUseCase listOAuthGrants,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getAccount = getAccount;
    this.knownDevices = knownDevices;
    this.socialIdentities = socialIdentities;
    this.oauthClientsProvider = oauthClientsProvider;
    this.listSessions = listSessions;
    this.listOAuthGrants = listOAuthGrants;
    this.organizationAccess =
        new PlatformAccountOrganizationAccess(organizationResolver, currentPlatformAccount);
  }

  // SDE-III review, 2026-09-19: impersonationToken/impersonationError arrive as one-time flash
  // attributes from PlatformAccountImpersonationController's own POST — Spring's
  // RedirectAttributes already puts them in this Model automatically, nothing to read explicitly.
  @GetMapping
  public String showProfile(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID accountId,
      @RequestParam(required = false) final String openImpersonate,
      final Model model) {
    final PlatformAccountOrganizationAccess.ResolvedAccountAccess access =
        organizationAccess.requireOwnedAccount(request, organizationId, accountId, getAccount);
    final Account account = access.account();
    final OrganizationId orgId = access.organizationId();
    final AccountId targetAccountId = account.id();

    model.addAttribute(ORGANIZATION_ID_ATTRIBUTE, organizationId);
    model.addAttribute(ORGANIZATION_NAME_ATTRIBUTE, access.organizationName());
    model.addAttribute("account", account);
    // organization-users.html's own row-menu "Impersonate user" link — see that file's own
    // comment for why this deep-links here instead of duplicating the impersonate flow.
    model.addAttribute("openImpersonate", openImpersonate != null);
    model.addAttribute("devices", knownDevices.findAllByAccountId(targetAccountId));
    model.addAttribute("socialIdentities", socialIdentities.findAllByAccountId(targetAccountId));
    model.addAttribute("oauthClients", oauthClientsProvider.forOrganization(orgId));

    // Clerk "View Profile" > Profile tab "Devices" parity (SDE-III review, 2026-09-21) — live
    // active sessions, not the known-device recognition history above (a different concept:
    // Device Trust recognition vs. "currently logged in right now"). Same friendly-label treatment
    // AccountSessionsController's own self-service page already establishes.
    final List<ActiveAccountSession> sessions =
        listSessions.handle(new ListActiveSessionsForAccountQuery(targetAccountId));
    model.addAttribute("sessions", sessions);
    model.addAttribute(
        "friendlyDeviceLabels",
        sessions.stream()
            .collect(
                Collectors.toMap(
                    ActiveAccountSession::sessionId,
                    session -> UserAgentLabel.friendly(session.userAgent()))));

    // Clerk "View Profile" > OAuth tab parity (ADR-0026).
    model.addAttribute(
        "oauthGrants", listOAuthGrants.handle(new ListOAuthGrantsForAccountQuery(targetAccountId)));
    return PROFILE_VIEW;
  }
}
