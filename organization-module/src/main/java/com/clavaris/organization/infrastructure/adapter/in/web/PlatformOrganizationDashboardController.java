package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.createorganization.CreateOrganizationCommand;
import com.clavaris.organization.application.usecase.createorganization.CreateOrganizationUseCase;
import com.clavaris.organization.application.usecase.listorganizationsforplatformaccount.ListOrganizationsForPlatformAccountQuery;
import com.clavaris.organization.application.usecase.listorganizationsforplatformaccount.ListOrganizationsForPlatformAccountUseCase;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * ADR-0012: the session-authenticated dashboard a {@code PlatformAccount} uses to create and list
 * its own Organizations — a distinct path from {@code POST /api/v1/admin/organizations} (the
 * Bearer-token/{@code PlatformClient} REST surface, unchanged), reached only after {@code
 * /platform/login}. {@code app}'s own {@code PlatformDashboardSecurityConfig} enforces the
 * authentication requirement; this controller only ever runs once a request already carries an
 * authenticated {@code PlatformAccount} session.
 */
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/platform/dashboard")
public class PlatformOrganizationDashboardController {

  private static final String DASHBOARD_VIEW = "organization/platform/dashboard";

  private final CreateOrganizationUseCase createOrganization;
  private final ListOrganizationsForPlatformAccountUseCase listOrganizations;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  public PlatformOrganizationDashboardController(
      final CreateOrganizationUseCase createOrganization,
      final ListOrganizationsForPlatformAccountUseCase listOrganizations,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.createOrganization = createOrganization;
    this.listOrganizations = listOrganizations;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  @GetMapping
  public String showDashboard(final HttpServletRequest request, final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    model.addAttribute(
        "organizations",
        listOrganizations.handle(
            new ListOrganizationsForPlatformAccountQuery(ownerPlatformAccountId)));
    model.addAttribute("form", new CreateOrganizationForm());
    return DASHBOARD_VIEW;
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String create(
      final HttpServletRequest request,
      @Valid @ModelAttribute("form") final CreateOrganizationForm form,
      final BindingResult bindingResult,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    if (bindingResult.hasErrors()) {
      model.addAttribute(
          "organizations",
          listOrganizations.handle(
              new ListOrganizationsForPlatformAccountQuery(ownerPlatformAccountId)));
      return DASHBOARD_VIEW;
    }

    // Not caught here: CreateOrganizationUseCase now validates ownerPlatformAccountId against a
    // real PlatformAccount (security finding, SDE-III review, 2026-08-22) and throws
    // PlatformAccountNotFoundException otherwise — but on this path the id is never caller
    // input, it's requireCurrentPlatformAccount()'s own resolved session principal, so a real
    // PlatformAccount existing is exactly what an authenticated session already guarantees. If
    // that guarantee is ever violated, an unhandled 500 here is a real, loud signal something
    // upstream is broken, same reasoning as requireCurrentPlatformAccount()'s own comment above.
    // TD-SEC-007: on this path, actor and owner are always the same PlatformAccount — genuine
    // self-service (ADR-0012), not an operator acting on someone else's behalf.
    createOrganization.handle(
        new CreateOrganizationCommand(
            form.getName(),
            ownerPlatformAccountId,
            AuditActor.platformAccount(ownerPlatformAccountId)));

    return "redirect:/platform/dashboard";
  }

  // Not expected to ever actually be empty — app's own security chain guarantees an authenticated
  // PlatformAccount before this controller runs — but a checked, explicit failure here is still
  // safer than an unchecked NoSuchElementException surfacing as an opaque 500 if that guarantee
  // were ever violated by a future wiring mistake.
  private UUID requireCurrentPlatformAccount(final HttpServletRequest request) {
    return currentPlatformAccount
        .resolve(request)
        .orElseThrow(
            () -> new IllegalStateException("No authenticated PlatformAccount on this request"));
  }
}
