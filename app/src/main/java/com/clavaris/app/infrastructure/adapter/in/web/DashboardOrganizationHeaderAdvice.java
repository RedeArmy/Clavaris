package com.clavaris.app.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.EnvironmentOption;
import com.clavaris.common.domain.model.OrganizationHeaderView;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.OrganizationEnvironment;
import com.clavaris.organization.infrastructure.adapter.in.web.CurrentPlatformAccountResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Adds the {@code organizationHeader} model attribute to every dashboard page under {@code
 * /platform/dashboard/organizations/{organizationId}/**}, so the Organization's name, environment,
 * creation date, ID and environment switcher stay visible above every tab (Workspaces, Users, Logs,
 * Configure and its sub-pages).
 *
 * <p>Lives in the app module because it is the one module allowed to depend on all the others: the
 * Users, Logs and Configure pages belong to identity-, client-registry- and webhook-module, none of
 * which can read an Organization. Doing this once here, rather than in each of ~20 controllers,
 * also means a future tab gets the header for free.
 *
 * <p><b>Environment switcher (Clerk's Development/Production dropdown).</b> Each environment is its
 * own {@code Organization} with its own accounts, keys and clients (ADR-0010); a DEVELOPMENT one
 * points at its PRODUCTION sibling through {@code linkedEnvironmentOrganizationId} once promoted,
 * and the sibling points back. Switching is therefore only navigation to the sibling's equivalent
 * page — the same top-level tab when it has one, the Organization's home otherwise (a deeper id
 * such as a client id would not exist on the other side). A DEVELOPMENT Organization that was never
 * promoted still lists Production, as a "set up" entry leading to Promote to Production. A
 * PRODUCTION Organization with no paired DEVELOPMENT one (every Organization that predates the
 * environments feature) has nothing to switch to, so it gets no switcher.
 *
 * <p>Same ownership rule as every dashboard controller: an Organization the signed-in
 * PlatformAccount does not own resolves to nothing (the page itself still 404s on its own), and a
 * paired sibling owned by someone else is never offered. HTMX fragment requests are skipped — they
 * swap content below the header, never the header itself.
 */
// java:S1075: BASE_PATH and the pattern built on it are routes this server-rendered app owns and
// serves itself (the Organization pages' own @RequestMapping), not an external URI a deployment
// should be able to repoint — the same "these are code, not runtime config" reasoning
// PlatformWorkspaceController's and PlatformWorkspaceRoleController's own identical suppressions
// already document. Making them configurable would only let the switcher links drift from the
// routes the controllers actually serve.
@SuppressWarnings("java:S1075")
@ControllerAdvice(annotations = Controller.class)
class DashboardOrganizationHeaderAdvice {

  private static final String BASE_PATH = "/platform/dashboard/organizations/";
  private static final Pattern ORGANIZATION_PAGE =
      Pattern.compile(
          "^/platform/dashboard/organizations/([0-9a-fA-F-]{36})(?:/([^/]+))?(?:/.*)?$");

  // The first path segment after the Organization id that names a whole page of its own, i.e. a
  // tab or Configure section that exists, with the same URL, in every Organization.
  private static final Set<String> SHARED_PAGES =
      Set.of(
          "users",
          "audit-log",
          "oauth-clients",
          "secret-keys",
          "signing-keys",
          "webhook-endpoints",
          "api-keys",
          "workspace-roles",
          "rate-limit-policy",
          "danger-zone");
  private static final String HX_REQUEST_HEADER = "HX-Request";

  private final OrganizationRepository organizations;
  private final CurrentPlatformAccountResolver currentAccount;

  /* package */ DashboardOrganizationHeaderAdvice(
      final OrganizationRepository organizations,
      final CurrentPlatformAccountResolver currentAccount) {
    this.organizations = organizations;
    this.currentAccount = currentAccount;
  }

  @ModelAttribute("organizationHeader")
  /* package */ OrganizationHeaderView organizationHeader(final HttpServletRequest request) {
    return pageOf(request).flatMap(page -> headerFor(request, page)).orElse(null);
  }

  // The Organization a dashboard URL points at, plus the shared page it is on (empty = the
  // Organization's home).
  private record Page(UUID organizationId, String sharedPage) {}

  private Optional<OrganizationHeaderView> headerFor(
      final HttpServletRequest request, final Page page) {
    return currentAccount
        .resolve(request)
        .flatMap(
            ownerId ->
                organizations
                    .findById(page.organizationId())
                    .filter(organization -> organization.ownerPlatformAccountId().equals(ownerId))
                    .map(organization -> toView(organization, ownerId, page.sharedPage())));
  }

  private OrganizationHeaderView toView(
      final Organization organization, final UUID ownerId, final String sharedPage) {
    return new OrganizationHeaderView(
        organization.id(),
        organization.name(),
        isProduction(organization),
        organization.createdAt(),
        environmentsFor(organization, ownerId, sharedPage));
  }

  private List<EnvironmentOption> environmentsFor(
      final Organization organization, final UUID ownerId, final String sharedPage) {
    final Optional<Organization> sibling =
        organization
            .linkedEnvironmentOrganizationId()
            .flatMap(organizations::findById)
            .filter(other -> other.ownerPlatformAccountId().equals(ownerId));
    return isProduction(organization)
        ? fromProduction(organization, sibling, sharedPage)
        : fromDevelopment(organization, sibling, sharedPage);
  }

  // A PRODUCTION Organization can only switch to the DEVELOPMENT one it was promoted from; with no
  // such sibling (every Organization that predates the environments feature) there is nothing to
  // offer and no switcher is shown.
  private static List<EnvironmentOption> fromProduction(
      final Organization organization,
      final Optional<Organization> development,
      final String sharedPage) {
    final EnvironmentOption here = optionFor(organization, true, sharedPage);
    return development
        .<List<EnvironmentOption>>map(other -> List.of(optionFor(other, false, sharedPage), here))
        .orElse(List.of());
  }

  // A DEVELOPMENT Organization always lists Production: its sibling when promoted, otherwise a
  // "set up" entry that leads to Promote to Production.
  private static List<EnvironmentOption> fromDevelopment(
      final Organization organization,
      final Optional<Organization> production,
      final String sharedPage) {
    final EnvironmentOption here = optionFor(organization, true, sharedPage);
    final EnvironmentOption other =
        production
            .map(sibling -> optionFor(sibling, false, sharedPage))
            .orElseGet(
                () ->
                    new EnvironmentOption(
                        true,
                        false,
                        false,
                        BASE_PATH + organization.id() + "/promote-to-production",
                        null));
    return List.of(here, other);
  }

  private static EnvironmentOption optionFor(
      final Organization organization, final boolean current, final String sharedPage) {
    final String page = sharedPage.isEmpty() ? "" : "/" + sharedPage;
    return new EnvironmentOption(
        isProduction(organization),
        current,
        true,
        BASE_PATH + organization.id() + page,
        organization.name());
  }

  private static boolean isProduction(final Organization organization) {
    return organization.environment() == OrganizationEnvironment.PRODUCTION;
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  private static Optional<Page> pageOf(final HttpServletRequest request) {
    if (isHtmxRequest(request)) {
      return Optional.empty();
    }
    final String path = request.getRequestURI().substring(request.getContextPath().length());
    final Matcher matcher = ORGANIZATION_PAGE.matcher(path);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    final String firstSegment = matcher.group(2);
    final String sharedPage =
        firstSegment != null && SHARED_PAGES.contains(firstSegment) ? firstSegment : "";
    try {
      return Optional.of(new Page(UUID.fromString(matcher.group(1)), sharedPage));
    } catch (final IllegalArgumentException _) {
      return Optional.empty();
    }
  }

  private static boolean isHtmxRequest(final HttpServletRequest request) {
    return "true".equals(request.getHeader(HX_REQUEST_HEADER));
  }
}
