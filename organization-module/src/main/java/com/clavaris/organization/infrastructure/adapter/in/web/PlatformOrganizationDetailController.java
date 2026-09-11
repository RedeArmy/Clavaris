package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountQuery;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.listworkspacesfororganization.ListWorkspacesForOrganizationQuery;
import com.clavaris.organization.application.usecase.listworkspacesfororganization.ListWorkspacesForOrganizationUseCase;
import com.clavaris.organization.domain.model.Organization;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * ADR-0025: the dashboard's own Organization-detail hub — {@code GET
 * /platform/dashboard/organizations/{organizationId}}. Read-only for this pass: shows the
 * Organization's own identity (name, environment, created date) and its Workspaces
 * (ListWorkspacesForOrganizationUseCase, already existed for the REST admin API — reused here
 * unchanged, not duplicated). Mutating actions from this page (promote to production, delete,
 * create/manage a Workspace, register an OAuth client) are a deliberately separate, later increment
 * — see technical-debt-register.md's own dashboard-rollout row for the full plan.
 *
 * <p>{@code organizationId} resolves through {@link GetOrganizationForPlatformAccountUseCase},
 * never a bare {@code OrganizationRepository} call from this controller — see that use case's own
 * Javadoc for the cross-tenant ownership check this indirection exists to enforce. A 404, not a
 * 403, for an Organization that exists but belongs to someone else — same anti-enumeration posture
 * that use case's own Javadoc documents.
 */
// PMD.LongVariable: currentPlatformAccount/ownerPlatformAccountId (field/constructor param/local
// each) are long by design, not accidentally — same class-level-suppression precedent
// PlatformOrganizationDashboardController's own identical rationale documents.
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}")
public class PlatformOrganizationDetailController {

  private static final String DETAIL_VIEW = "organization/platform/organization-detail";

  private final GetOrganizationForPlatformAccountUseCase getOrganization;
  private final ListWorkspacesForOrganizationUseCase listWorkspaces;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  public PlatformOrganizationDetailController(
      final GetOrganizationForPlatformAccountUseCase getOrganization,
      final ListWorkspacesForOrganizationUseCase listWorkspaces,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getOrganization = getOrganization;
    this.listWorkspaces = listWorkspaces;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  @GetMapping
  public String showDetail(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
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
    model.addAttribute(
        "workspaces",
        listWorkspaces.handle(new ListWorkspacesForOrganizationQuery(organizationId)));
    return DETAIL_VIEW;
  }
}
