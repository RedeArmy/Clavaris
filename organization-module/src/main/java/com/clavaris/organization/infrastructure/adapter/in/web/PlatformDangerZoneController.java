package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountQuery;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
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
 * SDE-III review, 2026-09-19 — Clerk-style "Configure" navigation: a real, standalone landing page
 * for the two destructive actions previously shell-carded directly on {@code
 * organization-detail.html} — {@code GET
 * /platform/dashboard/organizations/{organizationId}/danger-zone}. Deliberately read-only, same as
 * {@code PlatformAuditLogController}: this page itself performs no mutation and needs no
 * confirmation flow of its own — Promote to Production and Delete Organization are still each their
 * own separate page with their own confirmation flow ({@code
 * PlatformCreateProductionEnvironmentController}/{@code PlatformDeleteOrganizationController}),
 * this page only links to them, exactly as {@code organization-detail.html}'s own former Danger
 * Zone section did.
 */
// PMD.LongVariable: currentPlatformAccount/ownerPlatformAccountId (field/constructor param/local
// each) are long by design, not accidentally — same class-level-suppression precedent
// PlatformOrganizationDashboardController's own identical rationale documents.
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/danger-zone")
public class PlatformDangerZoneController {

  private static final String DANGER_ZONE_VIEW = "organization/platform/organization-danger-zone";

  private final GetOrganizationForPlatformAccountUseCase getOrganization;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  public PlatformDangerZoneController(
      final GetOrganizationForPlatformAccountUseCase getOrganization,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getOrganization = getOrganization;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  @GetMapping
  public String show(
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
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

    model.addAttribute("organization", organization);
    return DANGER_ZONE_VIEW;
  }
}
