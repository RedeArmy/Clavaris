package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountQuery;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.getratelimitpolicyfororganization.GetRateLimitPolicyForOrganizationUseCase;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * TD-FUT-002 (self-service tuning, shipped): the dashboard's own read+write path for an
 * Organization's capacity ceiling — {@code GET}/{@code POST
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
 * <p>SDE-III review, 2026-09-19 — Clerk-style "Configure" navigation: this used to be an inline
 * section/write-only controller for {@code PlatformOrganizationDetailController}'s own page; now a
 * standalone page of its own ({@code organization-rate-limit.html}), same shape as Secret
 * Keys/OAuth Clients/Signing Keys/Webhook Endpoints — this class gained the {@code GetMapping} that
 * page needed (previously the display-only read lived on {@code
 * PlatformOrganizationDetailController}'s own GET). Same HTMX-fragment-vs-redirect convention as
 * every other dashboard mutation: an {@code HX-Request} gets back just the {@code rateLimit}
 * fragment, re-rendered in place; a plain form submit redirects back to this same page.
 */
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/rate-limit-policy")
public class PlatformRateLimitPolicyController {

  private static final String RATE_LIMIT_VIEW = "organization/platform/organization-rate-limit";
  private static final String RATE_LIMIT_FRAGMENT = RATE_LIMIT_VIEW + " :: rateLimit";
  private static final String ORGANIZATION_ID_ATTRIBUTE = "organizationId";
  private static final String ORGANIZATION_NAME_ATTRIBUTE = "organizationName";
  private static final String RATE_LIMIT_POLICY_ATTRIBUTE = "rateLimitPolicy";
  private static final String RATE_LIMIT_FORM_ATTRIBUTE = "rateLimitForm";

  // HTMX's own request header (https://htmx.org/reference/#request_headers) — same convention as
  // every other dashboard controller's own identical constant.
  private static final String HX_REQUEST_HEADER = "HX-Request";

  private final GetOrganizationForPlatformAccountUseCase getOrganization;
  private final SetRateLimitPolicyForOrganizationUseCase setRateLimitPolicy;
  private final GetRateLimitPolicyForOrganizationUseCase getRateLimitPolicy;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  public PlatformRateLimitPolicyController(
      final GetOrganizationForPlatformAccountUseCase getOrganization,
      final SetRateLimitPolicyForOrganizationUseCase setRateLimitPolicy,
      final GetRateLimitPolicyForOrganizationUseCase getRateLimitPolicy,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getOrganization = getOrganization;
    this.setRateLimitPolicy = setRateLimitPolicy;
    this.getRateLimitPolicy = getRateLimitPolicy;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  @GetMapping
  public String show(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final Organization organization =
        requireOwnedOrganization(organizationId, ownerPlatformAccountId);

    model.addAttribute(ORGANIZATION_ID_ATTRIBUTE, organizationId);
    model.addAttribute(ORGANIZATION_NAME_ATTRIBUTE, organization.name());
    model.addAttribute(RATE_LIMIT_POLICY_ATTRIBUTE, getRateLimitPolicy.handle(organizationId));
    model.addAttribute(RATE_LIMIT_FORM_ATTRIBUTE, new SetRateLimitPolicyForm());
    return RATE_LIMIT_VIEW;
  }

  // Three exits (validation error, hard-cap-exceeded error, HTMX fragment vs. plain redirect) —
  // same rationale as every other dashboard mutation's own identical suppression.
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
      model.addAttribute(ORGANIZATION_ID_ATTRIBUTE, organizationId);
      model.addAttribute(ORGANIZATION_NAME_ATTRIBUTE, organization.name());
      model.addAttribute(RATE_LIMIT_POLICY_ATTRIBUTE, getRateLimitPolicy.handle(organizationId));
      model.addAttribute(RATE_LIMIT_FORM_ATTRIBUTE, new SetRateLimitPolicyForm());
      return RATE_LIMIT_FRAGMENT;
    }
    return "redirect:/platform/dashboard/organizations/" + organizationId + "/rate-limit-policy";
  }

  private String rerenderWithError(
      final HttpServletRequest request,
      final Model model,
      final Organization organization,
      final UUID organizationId) {
    model.addAttribute(ORGANIZATION_ID_ATTRIBUTE, organizationId);
    model.addAttribute(ORGANIZATION_NAME_ATTRIBUTE, organization.name());
    model.addAttribute(RATE_LIMIT_POLICY_ATTRIBUTE, getRateLimitPolicy.handle(organizationId));
    return isHtmxRequest(request) ? RATE_LIMIT_FRAGMENT : RATE_LIMIT_VIEW;
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
