package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.getauditlogforaccount.GetAuditLogForAccountUseCase;
import com.clavaris.identity.domain.model.Account;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * SDE-III review, 2026-09-19 — Clerk dashboard "Users" tab "View log" menu item: a single Account's
 * own audit trail, via {@link GetAuditLogForAccountUseCase}. Deliberately a separate, simpler page
 * from {@code PlatformAuditLogController} (the Organization-wide view) — that view's own Javadoc
 * documents why Account-level events are out of scope there; this page is exactly the complement.
 */
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/users/{accountId}/audit-log")
public class PlatformAccountAuditLogController {

  private static final String AUDIT_LOG_VIEW = "identity/platform/account-audit-log";
  private static final String ORGANIZATION_ID_ATTRIBUTE = "organizationId";
  private static final String ORGANIZATION_NAME_ATTRIBUTE = "organizationName";

  private final GetAccountForOrganizationUseCase getAccount;
  private final GetAuditLogForAccountUseCase getAuditLog;
  private final PlatformAccountOrganizationAccess organizationAccess;

  public PlatformAccountAuditLogController(
      final GetAccountForOrganizationUseCase getAccount,
      final GetAuditLogForAccountUseCase getAuditLog,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getAccount = getAccount;
    this.getAuditLog = getAuditLog;
    this.organizationAccess =
        new PlatformAccountOrganizationAccess(organizationResolver, currentPlatformAccount);
  }

  @GetMapping
  public String showAuditLog(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID accountId,
      final Model model) {
    final PlatformAccountOrganizationAccess.ResolvedAccountAccess access =
        organizationAccess.requireOwnedAccount(request, organizationId, accountId, getAccount);
    final Account account = access.account();

    model.addAttribute(ORGANIZATION_ID_ATTRIBUTE, organizationId);
    model.addAttribute(ORGANIZATION_NAME_ATTRIBUTE, access.organizationName());
    model.addAttribute("account", account);
    model.addAttribute("auditEvents", getAuditLog.handle(accountId));
    return AUDIT_LOG_VIEW;
  }
}
