package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.application.usecase.getauditlogfororganization.GetAuditLogForOrganizationUseCase;
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
 * ADR-0025: the dashboard's own audit-log view — {@code GET
 * /platform/dashboard/organizations/{organizationId}/audit-log}. The first fully read-only
 * controller in this codebase's dashboard: no {@code @PostMapping}, no form, no HTMX fragment — a
 * plain bounded list, same "no real pagination yet" posture {@link
 * GetAuditLogForOrganizationUseCase}'s own Javadoc documents for the underlying query. Linked from
 * {@code organization-detail.html} as its own page rather than inlined (unlike the small Rate Limit
 * display) — up to 100 rows doesn't belong crammed into that page the way a single-value display
 * does.
 *
 * <p>{@code organizationId} resolves through {@link GetOrganizationForPlatformAccountUseCase} —
 * never a bare repository call — same anti-enumeration posture every other dashboard controller in
 * this codebase already establishes: an organizationId this {@code PlatformAccount} doesn't own
 * 404s identically to one that doesn't exist.
 */
// PMD.LongVariable: currentPlatformAccount/ownerPlatformAccountId are long by design, not
// accidentally — same class-level-suppression precedent PlatformOrganizationDetailController's
// own identical rationale documents.
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/audit-log")
public class PlatformAuditLogController {

  private static final String AUDIT_LOG_VIEW = "organization/platform/audit-log";

  private final GetOrganizationForPlatformAccountUseCase getOrganization;
  private final GetAuditLogForOrganizationUseCase getAuditLog;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  public PlatformAuditLogController(
      final GetOrganizationForPlatformAccountUseCase getOrganization,
      final GetAuditLogForOrganizationUseCase getAuditLog,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getOrganization = getOrganization;
    this.getAuditLog = getAuditLog;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  @GetMapping
  public String showAuditLog(
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
    model.addAttribute("auditEvents", getAuditLog.handle(organizationId));
    return AUDIT_LOG_VIEW;
  }
}
