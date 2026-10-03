package com.clavaris.app.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.OrganizationHeaderView;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.OrganizationEnvironment;
import com.clavaris.organization.infrastructure.adapter.in.web.CurrentPlatformAccountResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Adds the {@code organizationHeader} model attribute to every dashboard page under {@code
 * /platform/dashboard/organizations/{organizationId}/**}, so the Organization's name, environment,
 * creation date and ID stay visible above every tab (Workspaces, Users, Logs, Configure and its
 * sub-pages) instead of only on the Workspaces tab.
 *
 * <p>Lives in the app module because it is the one module allowed to depend on all the others: the
 * Users, Logs and Configure pages belong to identity-, client-registry- and webhook-module, none of
 * which can read an Organization. Doing this once here, rather than in each of ~20 controllers,
 * also means a future tab gets the header for free.
 *
 * <p>Same ownership rule as every dashboard controller: an Organization the signed-in
 * PlatformAccount does not own resolves to nothing (the page itself still 404s on its own). HTMX
 * fragment requests are skipped — they swap content below the header, never the header itself.
 */
@ControllerAdvice(annotations = Controller.class)
class DashboardOrganizationHeaderAdvice {

  private static final Pattern ORGANIZATION_PAGE =
      Pattern.compile("^/platform/dashboard/organizations/([0-9a-fA-F-]{36})(?:/.*)?$");
  private static final String HX_REQUEST_HEADER = "HX-Request";

  private final OrganizationRepository organizations;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  /* package */ DashboardOrganizationHeaderAdvice(
      final OrganizationRepository organizations,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.organizations = organizations;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  @ModelAttribute("organizationHeader")
  /* package */ OrganizationHeaderView organizationHeader(final HttpServletRequest request) {
    return organizationIdOf(request)
        .flatMap(organizationId -> headerFor(request, organizationId))
        .orElse(null);
  }

  private Optional<OrganizationHeaderView> headerFor(
      final HttpServletRequest request, final UUID organizationId) {
    return currentPlatformAccount
        .resolve(request)
        .flatMap(
            ownerId ->
                organizations
                    .findById(organizationId)
                    .filter(organization -> organization.ownerPlatformAccountId().equals(ownerId)))
        .map(DashboardOrganizationHeaderAdvice::toView);
  }

  private static OrganizationHeaderView toView(final Organization organization) {
    return new OrganizationHeaderView(
        organization.id(),
        organization.name(),
        organization.environment() == OrganizationEnvironment.PRODUCTION,
        organization.createdAt());
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  private static Optional<UUID> organizationIdOf(final HttpServletRequest request) {
    if ("true".equals(request.getHeader(HX_REQUEST_HEADER))) {
      return Optional.empty();
    }
    final String path = request.getRequestURI().substring(request.getContextPath().length());
    final Matcher matcher = ORGANIZATION_PAGE.matcher(path);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(matcher.group(1)));
    } catch (IllegalArgumentException malformed) {
      return Optional.empty();
    }
  }
}
