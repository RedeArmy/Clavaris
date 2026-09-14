package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountQuery;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.getratelimitpolicyfororganization.GetRateLimitPolicyForOrganizationUseCase;
import com.clavaris.organization.application.usecase.listworkspacesfororganizationpaged.ListWorkspacesForOrganizationPagedQuery;
import com.clavaris.organization.application.usecase.listworkspacesfororganizationpaged.ListWorkspacesForOrganizationPagedUseCase;
import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.OrganizationNotFoundException;
import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.SetRateLimitPolicyForOrganizationCommand;
import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.SetRateLimitPolicyForOrganizationUseCase;
import com.clavaris.organization.domain.model.Organization;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * TD-FUT-002 (self-service tuning, shipped): the dashboard's own write path for an Organization's
 * capacity ceiling — {@code POST
 * /platform/dashboard/organizations/{organizationId}/rate-limit-policy}. Every write here goes
 * through the exact same {@link SetRateLimitPolicyForOrganizationUseCase} the REST admin API
 * ({@code SetRateLimitPolicyController}, still {@code PlatformClient}-gated, unchanged) already
 * used — this controller adds a second, session-authenticated {@link AuditActor#platformAccount}
 * caller, same widening precedent {@code RotateSigningKeyForOrganizationCommand}'s own Javadoc
 * already documents for signing-key rotation.
 *
 * <p>ADR-0010 §6.2's own "tunable within a hard system-wide cap" wording draws no distinction
 * between an operator-set and a tenant-set value — this path enforces the exact same {@code
 * RateLimitPolicy#withRequestsPerMinute}/{@code #define} hard-cap check the REST path does, not a
 * narrower self-service sub-ceiling. What's new here relative to the REST path is the ownership
 * check: {@code organizationId} resolves through {@link GetOrganizationForPlatformAccountUseCase}
 * first (never a bare {@code organizations.existsById} call, unlike the REST path, which is
 * deliberately unscoped since a {@code PlatformClient} token represents Clavaris operating on any
 * tenant) — an organizationId belonging to someone else's Organization resolves identically to
 * "doesn't exist," same anti-enumeration posture as every other dashboard controller.
 *
 * <p>This page's Rate Limit section stays inline on {@code PlatformOrganizationDetailController}'s
 * own organization-detail page rather than a separate linked-out page (unlike Secret Keys/OAuth
 * Clients/Signing Keys/Webhook Endpoints) — a single-value settings form, not a list, the same
 * "inlined, not linked out" shape that page's own Rate Limit section already had before this
 * revision made it writable. Same HTMX-fragment-vs-redirect convention as every other dashboard
 * mutation: an {@code HX-Request} gets back just the {@code rateLimit} fragment, re-rendered in
 * place; a plain form submit gets a full redirect.
 */
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/rate-limit-policy")
public class PlatformRateLimitPolicyController {

  private static final String ORGANIZATION_DETAIL_VIEW =
      "organization/platform/organization-detail";
  private static final String RATE_LIMIT_FRAGMENT = ORGANIZATION_DETAIL_VIEW + " :: rateLimit";
  private static final String ORGANIZATION_ATTRIBUTE = "organization";
  private static final String RATE_LIMIT_POLICY_ATTRIBUTE = "rateLimitPolicy";
  private static final String RATE_LIMIT_FORM_ATTRIBUTE = "rateLimitForm";

  // HTMX's own request header (https://htmx.org/reference/#request_headers) — same convention as
  // every other dashboard controller's own identical constant.
  private static final String HX_REQUEST_HEADER = "HX-Request";

  private final GetOrganizationForPlatformAccountUseCase getOrganization;
  private final SetRateLimitPolicyForOrganizationUseCase setRateLimitPolicy;
  private final GetRateLimitPolicyForOrganizationUseCase getRateLimitPolicy;
  private final ListWorkspacesForOrganizationPagedUseCase listWorkspaces;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  public PlatformRateLimitPolicyController(
      final GetOrganizationForPlatformAccountUseCase getOrganization,
      final SetRateLimitPolicyForOrganizationUseCase setRateLimitPolicy,
      final GetRateLimitPolicyForOrganizationUseCase getRateLimitPolicy,
      final ListWorkspacesForOrganizationPagedUseCase listWorkspaces,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getOrganization = getOrganization;
    this.setRateLimitPolicy = setRateLimitPolicy;
    this.getRateLimitPolicy = getRateLimitPolicy;
    this.listWorkspaces = listWorkspaces;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  // Four exits (validation error, hard-cap-exceeded error, HTMX fragment, plain redirect) — same
  // rationale as every other dashboard mutation's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String set(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute(RATE_LIMIT_FORM_ATTRIBUTE) final SetRateLimitPolicyForm form,
      final BindingResult bindingResult,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final Organization organization =
        requireOwnedOrganization(organizationId, ownerPlatformAccountId);

    if (bindingResult.hasErrors()) {
      return rerenderWithError(request, model, organization, organizationId);
    }

    try {
      setRateLimitPolicy.handle(
          new SetRateLimitPolicyForOrganizationCommand(
              organizationId,
              form.getRequestsPerMinute(),
              AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final OrganizationNotFoundException _) {
      // Not expected on this path — requireOwnedOrganization above already confirmed
      // organizationId exists and is owned — but a loud 404 is still safer than assuming that
      // guarantee can never race with a concurrent deletion, same defensive posture every other
      // dashboard mutation controller in this codebase already applies to its own "not expected"
      // catch block.
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final IllegalArgumentException _) {
      // RateLimitPolicy's own factory/update methods throw this for exactly one reason at this
      // call site: requestsPerMinute exceeded the hard system-wide cap (ADR-0010 §6.2) —
      // @Positive on the form already ruled out the only other case (a non-positive value) before
      // this method body ever ran, same as SetRateLimitPolicyController's own identical REST-path
      // reasoning.
      model.addAttribute("hardCapExceededError", true);
      return rerenderWithError(request, model, organization, organizationId);
    }

    if (isHtmxRequest(request)) {
      model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
      model.addAttribute(RATE_LIMIT_POLICY_ATTRIBUTE, getRateLimitPolicy.handle(organizationId));
      model.addAttribute(RATE_LIMIT_FORM_ATTRIBUTE, new SetRateLimitPolicyForm());
      return RATE_LIMIT_FRAGMENT;
    }
    return "redirect:/platform/dashboard/organizations/" + organizationId;
  }

  // A validation/hard-cap error re-renders the WHOLE organization-detail page on a plain
  // (non-HTMX) submit — same "the page's own other sections must all still be populated" gap
  // PlatformWorkspaceController#create's own identical branch already documents (and was itself
  // once a real NPE this codebase already fixed once, 2026-09-12, for the same underlying page).
  // Two exits (HTMX fragment vs. full page) — same rationale as every other dashboard mutation's
  // own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  private String rerenderWithError(
      final HttpServletRequest request,
      final Model model,
      final Organization organization,
      final UUID organizationId) {
    model.addAttribute(RATE_LIMIT_POLICY_ATTRIBUTE, getRateLimitPolicy.handle(organizationId));
    if (isHtmxRequest(request)) {
      model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
      return RATE_LIMIT_FRAGMENT;
    }
    model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
    addWorkspacesToModel(model, organizationId);
    model.addAttribute("workspaceForm", new CreateWorkspaceForm());
    return ORGANIZATION_DETAIL_VIEW;
  }

  private void addWorkspacesToModel(final Model model, final UUID organizationId) {
    final KeysetPage<com.clavaris.organization.domain.model.Workspace> workspacesPage =
        listWorkspaces.handle(
            new ListWorkspacesForOrganizationPagedQuery(organizationId, KeysetPageRequest.first()));
    model.addAttribute("workspaces", workspacesPage.content());
    model.addAttribute("workspacesPage", workspacesPage);
  }

  private Organization requireOwnedOrganization(
      final UUID organizationId, final UUID ownerPlatformAccountId) {
    return getOrganization
        .handle(new GetOrganizationForPlatformAccountQuery(organizationId, ownerPlatformAccountId))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private static boolean isHtmxRequest(final HttpServletRequest request) {
    return "true".equals(request.getHeader(HX_REQUEST_HEADER));
  }

  private UUID requireCurrentPlatformAccount(final HttpServletRequest request) {
    return currentPlatformAccount
        .resolve(request)
        .orElseThrow(
            () -> new IllegalStateException("No authenticated PlatformAccount on this request"));
  }
}
