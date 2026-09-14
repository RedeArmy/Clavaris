package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountQuery;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.getratelimitpolicyfororganization.GetRateLimitPolicyForOrganizationUseCase;
import com.clavaris.organization.application.usecase.listworkspacesfororganizationpaged.ListWorkspacesForOrganizationPagedQuery;
import com.clavaris.organization.application.usecase.listworkspacesfororganizationpaged.ListWorkspacesForOrganizationPagedUseCase;
import com.clavaris.organization.domain.model.Organization;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * ADR-0025: the dashboard's own Organization-detail hub — {@code GET
 * /platform/dashboard/organizations/{organizationId}}. This controller itself stays read-only: the
 * page's own "create workspace" form (bound to the {@code workspaceForm} attribute added below)
 * posts to {@link PlatformWorkspaceController}, a separate controller, not a method here — same
 * split {@link PlatformOrganizationDashboardController}/this class already have for Organizations.
 * Other mutating actions on this page (promote to production, delete, register an OAuth client)
 * remain a deliberately separate, later increment — see technical-debt-register.md's own
 * dashboard-rollout row for the full plan.
 *
 * <p>{@code organizationId} resolves through {@link GetOrganizationForPlatformAccountUseCase},
 * never a bare {@code OrganizationRepository} call from this controller — see that use case's own
 * Javadoc for the cross-tenant ownership check this indirection exists to enforce. A 404, not a
 * 403, for an Organization that exists but belongs to someone else — same anti-enumeration posture
 * that use case's own Javadoc documents.
 *
 * <p>The {@code rateLimitPolicy} attribute (backed by {@link
 * GetRateLimitPolicyForOrganizationUseCase}) is deliberately display-only — TD-FUT-002/ADR-0010
 * §6.2 keep tuning this ceiling operator-managed only in v1, so unlike every other section on this
 * page there is no form or link-out to change it here, only the current effective value.
 */
// PMD.LongVariable: currentPlatformAccount/ownerPlatformAccountId (field/constructor param/local
// each) are long by design, not accidentally — same class-level-suppression precedent
// PlatformOrganizationDashboardController's own identical rationale documents.
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}")
public class PlatformOrganizationDetailController {

  private static final String DETAIL_VIEW = "organization/platform/organization-detail";
  private static final String WORKSPACES_FRAGMENT = DETAIL_VIEW + " :: workspaces";

  // HTMX's own request header (https://htmx.org/reference/#request_headers) — same convention as
  // PlatformOrganizationDashboardController's own identical constant.
  private static final String HX_REQUEST_HEADER = "HX-Request";

  private final GetOrganizationForPlatformAccountUseCase getOrganization;
  private final ListWorkspacesForOrganizationPagedUseCase listWorkspaces;
  private final GetRateLimitPolicyForOrganizationUseCase getRateLimitPolicy;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  public PlatformOrganizationDetailController(
      final GetOrganizationForPlatformAccountUseCase getOrganization,
      final ListWorkspacesForOrganizationPagedUseCase listWorkspaces,
      final GetRateLimitPolicyForOrganizationUseCase getRateLimitPolicy,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getOrganization = getOrganization;
    this.listWorkspaces = listWorkspaces;
    this.getRateLimitPolicy = getRateLimitPolicy;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  // TD-PERF-020 (keyset revision, 2026-09-14): ?after=/?before= carry an opaque KeysetCursor
  // token — see PlatformOrganizationDashboardController's own identical parameter for the full
  // reasoning. This GET also branches on HX-Request — new for this controller (every prior GET
  // across this dashboard always rendered the full page, since nothing on a plain GET ever needed
  // a fragment before pagination's own Previous/Next links did): a pagination link is itself an
  // hx-get, and its hx-target (#workspaces-content) can't safely receive a full HTML document the
  // way hx-swap="outerHTML" would otherwise apply it.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @GetMapping
  public String showDetail(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @RequestParam(required = false) final String after,
      @RequestParam(required = false) final String before,
      final Model model) {
    final UUID ownerPlatformAccountId =
        currentPlatformAccount
            .resolve(request)
            .orElseThrow(
                () ->
                    new IllegalStateException("No authenticated PlatformAccount on this request"));

    final Organization organization =
        getOrganization
            .handle(
                new GetOrganizationForPlatformAccountQuery(organizationId, ownerPlatformAccountId))
            // Same "unknown and not-yours look identical" posture as the use case's own Javadoc —
            // a plain 404, no detail on which check failed.
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

    model.addAttribute("organization", organization);
    addWorkspacesToModel(model, organizationId, KeysetPageRequest.fromCursors(after, before));
    model.addAttribute("workspaceForm", new CreateWorkspaceForm());
    if (isHtmxRequest(request)) {
      return WORKSPACES_FRAGMENT;
    }
    model.addAttribute("rateLimitPolicy", getRateLimitPolicy.handle(organizationId));
    return DETAIL_VIEW;
  }

  private static boolean isHtmxRequest(final HttpServletRequest request) {
    return "true".equals(request.getHeader(HX_REQUEST_HEADER));
  }

  private void addWorkspacesToModel(
      final Model model, final UUID organizationId, final KeysetPageRequest pageRequest) {
    final KeysetPage<com.clavaris.organization.domain.model.Workspace> workspacesPage =
        listWorkspaces.handle(
            new ListWorkspacesForOrganizationPagedQuery(organizationId, pageRequest));
    model.addAttribute("workspaces", workspacesPage.content());
    model.addAttribute("workspacesPage", workspacesPage);
  }
}
