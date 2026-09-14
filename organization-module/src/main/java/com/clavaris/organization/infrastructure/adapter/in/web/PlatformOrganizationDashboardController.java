package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.organization.application.usecase.createorganization.CreateOrganizationCommand;
import com.clavaris.organization.application.usecase.createorganization.CreateOrganizationUseCase;
import com.clavaris.organization.application.usecase.listorganizationsforplatformaccountpaged.ListOrganizationsForPlatformAccountPagedQuery;
import com.clavaris.organization.application.usecase.listorganizationsforplatformaccountpaged.ListOrganizationsForPlatformAccountPagedUseCase;
import com.clavaris.organization.domain.model.Organization;
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
import org.springframework.web.bind.annotation.RequestParam;

/**
 * ADR-0012: the session-authenticated dashboard a {@code PlatformAccount} uses to create and list
 * its own Organizations — a distinct path from {@code POST /api/v1/admin/organizations} (the
 * Bearer-token/{@code PlatformClient} REST surface, unchanged), reached only after {@code
 * /platform/login}. {@code app}'s own {@code PlatformDashboardSecurityConfig} enforces the
 * authentication requirement; this controller only ever runs once a request already carries an
 * authenticated {@code PlatformAccount} session.
 *
 * <p>ADR-0025: {@code create} below branches on HTMX's own {@code HX-Request} header — present, it
 * returns just the {@code content} fragment (dashboard.html's own {@code th:fragment="content"}
 * div, covering both the organizations table and the create form) so {@code hx-target="
 * #dashboard-content"}/{@code hx-swap="outerHTML"} on the page's own form can swap it in without a
 * full navigation; absent (JavaScript disabled, or a non-browser client), the exact same {@code
 * redirect:}/full-page-render behavior this controller already had is unchanged — HTMX is a
 * progressive enhancement here, never a requirement for this page to work.
 */
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/platform/dashboard")
public class PlatformOrganizationDashboardController {

  private static final String DASHBOARD_VIEW = "organization/platform/dashboard";
  private static final String CONTENT_FRAGMENT = DASHBOARD_VIEW + " :: content";
  private static final String ORGANIZATIONS_ATTRIBUTE = "organizations";
  private static final String PAGE_ATTRIBUTE = "organizationsPage";

  // HTMX's own request header (https://htmx.org/reference/#request_headers) — present on every
  // request HTMX itself issues, absent on an ordinary browser navigation/form submit.
  private static final String HX_REQUEST_HEADER = "HX-Request";

  private final CreateOrganizationUseCase createOrganization;
  private final ListOrganizationsForPlatformAccountPagedUseCase listOrganizations;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  public PlatformOrganizationDashboardController(
      final CreateOrganizationUseCase createOrganization,
      final ListOrganizationsForPlatformAccountPagedUseCase listOrganizations,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.createOrganization = createOrganization;
    this.listOrganizations = listOrganizations;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  // TD-PERF-020 (keyset revision, 2026-09-14): ?after=/?before= carry an opaque KeysetCursor
  // token, never a raw page number — see KeysetPageRequest's own Javadoc. A malformed/tampered
  // token surfaces as a 500 via KeysetCursor#decode, same "not a real user flow, a hard failure is
  // acceptable" posture the page-number version this replaces already documented.
  @GetMapping
  public String showDashboard(
      final HttpServletRequest request,
      @RequestParam(required = false) final String after,
      @RequestParam(required = false) final String before,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    addOrganizationsToModel(
        model, ownerPlatformAccountId, KeysetPageRequest.fromCursors(after, before));
    model.addAttribute("form", new CreateOrganizationForm());
    return DASHBOARD_VIEW;
  }

  private void addOrganizationsToModel(
      final Model model, final UUID ownerPlatformAccountId, final KeysetPageRequest pageRequest) {
    final KeysetPage<Organization> organizationsPage =
        listOrganizations.handle(
            new ListOrganizationsForPlatformAccountPagedQuery(ownerPlatformAccountId, pageRequest));
    model.addAttribute(ORGANIZATIONS_ATTRIBUTE, organizationsPage.content());
    model.addAttribute(PAGE_ATTRIBUTE, organizationsPage);
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
      addOrganizationsToModel(model, ownerPlatformAccountId, KeysetPageRequest.first());
      return isHtmxRequest(request) ? CONTENT_FRAGMENT : DASHBOARD_VIEW;
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

    if (isHtmxRequest(request)) {
      // Re-rendered directly (200), not "redirect:" — HTMX's own redirect-following would mean a
      // second, full-navigation round trip for a request whose whole point was avoiding one. A
      // fresh, blank CreateOrganizationForm is exactly what a real GET would also produce. The
      // first page (no cursor) — a newly-created Organization sorts first (newest-first
      // ordering), so this is exactly where it becomes visible, same as a real GET with no
      // ?after=/?before= would show too.
      addOrganizationsToModel(model, ownerPlatformAccountId, KeysetPageRequest.first());
      model.addAttribute("form", new CreateOrganizationForm());
      return CONTENT_FRAGMENT;
    }
    return "redirect:/platform/dashboard";
  }

  private static boolean isHtmxRequest(final HttpServletRequest request) {
    return "true".equals(request.getHeader(HX_REQUEST_HEADER));
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
