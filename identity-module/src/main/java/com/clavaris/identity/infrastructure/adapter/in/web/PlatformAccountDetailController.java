package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.SocialIdentityRepository;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.getloginactivityforaccount.GetLoginActivityForAccountQuery;
import com.clavaris.identity.application.usecase.getloginactivityforaccount.GetLoginActivityForAccountService;
import com.clavaris.identity.application.usecase.getloginactivityforaccount.GetLoginActivityForAccountUseCase;
import com.clavaris.identity.application.usecase.getloginactivityforaccount.LoginActivityDay;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * Account detail page (identity fields, known devices, linked social providers). Metadata and the
 * activity heatmap now shipped (TD-FUT-034 — its own {@code PlatformAccountMetadataController}
 * handles the Metadata tab's write side; {@code loginActivity} below feeds the heatmap) —
 * biometric/WebAuthn credentials remain out of scope, still tracked under the same TD-FUT-034 row
 * (`technical-debt-register.md`).
 *
 * <p>{@code organizationId} resolves through {@link OrganizationForPlatformAccountResolver}, same
 * anti-enumeration posture as {@link PlatformAccountsController}; a mismatched {@code accountId}
 * (wrong Organization, or none at all) 404s identically via {@link
 * GetAccountForOrganizationUseCase}'s own Organization-scoped lookup.
 */
// PMD.CouplingBetweenObjects: TD-FUT-034's own new GetLoginActivityForAccountUseCase collaborator
// pushed this class's own count past the threshold — same "wiring, not sprawl" reasoning as
// SocialLoginAuthenticationSuccessHandler's own identical suppression already documents.
@SuppressWarnings({"PMD.LongVariable", "PMD.CouplingBetweenObjects"})
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/users/{accountId}")
public class PlatformAccountDetailController {

  private static final String PROFILE_VIEW = "identity/platform/account-profile";
  private static final String ORGANIZATION_ID_ATTRIBUTE = "organizationId";
  private static final String ORGANIZATION_NAME_ATTRIBUTE = "organizationName";
  private static final int DAYS_PER_WEEK = 7;
  private static final long HEATMAP_LEVEL_1_MAX_COUNT = 1;
  private static final long HEATMAP_LEVEL_2_MAX_COUNT = 3;
  private static final long HEATMAP_LEVEL_3_MAX_COUNT = 6;

  private final GetAccountForOrganizationUseCase getAccount;
  private final KnownDeviceRepository knownDevices;
  private final SocialIdentityRepository socialIdentities;
  private final OAuthClientsForOrganizationProvider oauthClientsProvider;
  private final ListActiveSessionsForAccountUseCase listSessions;
  private final ListOAuthGrantsForAccountUseCase listOAuthGrants;
  private final GetLoginActivityForAccountUseCase getLoginActivity;
  private final PlatformAccountOrganizationAccess organizationAccess;

  @SuppressWarnings("java:S107")
  public PlatformAccountDetailController(
      final GetAccountForOrganizationUseCase getAccount,
      final KnownDeviceRepository knownDevices,
      final SocialIdentityRepository socialIdentities,
      final OAuthClientsForOrganizationProvider oauthClientsProvider,
      final ListActiveSessionsForAccountUseCase listSessions,
      final ListOAuthGrantsForAccountUseCase listOAuthGrants,
      final GetLoginActivityForAccountUseCase getLoginActivity,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getAccount = getAccount;
    this.knownDevices = knownDevices;
    this.socialIdentities = socialIdentities;
    this.oauthClientsProvider = oauthClientsProvider;
    this.listSessions = listSessions;
    this.listOAuthGrants = listOAuthGrants;
    this.getLoginActivity = getLoginActivity;
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

    // TD-FUT-034, Clerk "View Profile" activity heatmap parity.
    model.addAttribute(
        "loginActivityWeeks",
        buildHeatmapWeeks(
            getLoginActivity.handle(new GetLoginActivityForAccountQuery(targetAccountId))));
    return PROFILE_VIEW;
  }

  // TD-FUT-034: turns the sparse day-count list GetLoginActivityForAccountUseCase returns into a
  // dense, Sunday-aligned calendar grid (same convention GitHub's own contribution graph uses) —
  // purely a rendering concern, so it lives here rather than in the use case itself. Grid padding
  // cells (outside the actual GetLoginActivityForAccountService.WINDOW_DAYS trailing window, needed
  // only to complete a partial first/last week) carry a null date and level 0.
  private static List<List<HeatmapDayCell>> buildHeatmapWeeks(
      final List<LoginActivityDay> activity) {
    final Map<LocalDate, Long> countsByDay =
        activity.stream()
            .collect(Collectors.toMap(LoginActivityDay::date, LoginActivityDay::count));
    final LocalDate today = LocalDate.now();
    final LocalDate windowStart =
        today.minusDays(GetLoginActivityForAccountService.WINDOW_DAYS - 1L);
    final LocalDate gridStart =
        windowStart.minusDays(windowStart.getDayOfWeek().getValue() % DAYS_PER_WEEK);

    final List<List<HeatmapDayCell>> weeks = new ArrayList<>();
    List<HeatmapDayCell> currentWeek = new ArrayList<>();
    for (LocalDate cursor = gridStart; !cursor.isAfter(today); cursor = cursor.plusDays(1)) {
      final boolean inWindow = !cursor.isBefore(windowStart);
      final long count = inWindow ? countsByDay.getOrDefault(cursor, 0L) : 0L;
      currentWeek.add(inWindow ? heatmapDayCell(cursor, count) : emptyHeatmapDayCell());
      if (currentWeek.size() == DAYS_PER_WEEK) {
        weeks.add(currentWeek);
        currentWeek = new ArrayList<>();
      }
    }
    if (!currentWeek.isEmpty()) {
      while (currentWeek.size() < DAYS_PER_WEEK) {
        currentWeek.add(emptyHeatmapDayCell());
      }
      weeks.add(currentWeek);
    }
    return weeks;
  }

  private static HeatmapDayCell heatmapDayCell(final LocalDate date, final long count) {
    final int level = heatmapLevel(count);
    return new HeatmapDayCell(
        date,
        count,
        level,
        "clavaris-heatmap__day clavaris-heatmap__day--level-" + level,
        date + ": " + count + " sign-in(s)");
  }

  private static HeatmapDayCell emptyHeatmapDayCell() {
    return new HeatmapDayCell(
        null, 0L, 0, "clavaris-heatmap__day clavaris-heatmap__day--empty", null);
  }

  // Fixed thresholds, same "a few named buckets, not a continuous scale" posture GitHub's own
  // contribution graph uses — no config surface, since nothing in this feature's scope needs one.
  // A ternary chain, not if/else: PMD.OnlyOneReturn (every other method in this class already
  // follows single-exit) is naturally satisfied by one expression rather than earning its own
  // suppression.
  private static int heatmapLevel(final long count) {
    return count <= 0
        ? 0
        : count <= HEATMAP_LEVEL_1_MAX_COUNT
            ? 1
            : count <= HEATMAP_LEVEL_2_MAX_COUNT ? 2 : count <= HEATMAP_LEVEL_3_MAX_COUNT ? 3 : 4;
  }
}
