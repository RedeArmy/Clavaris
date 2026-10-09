package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountQuery;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.getsessionpolicyfororganization.GetSessionPolicyForOrganizationUseCase;
import com.clavaris.organization.application.usecase.setsessionpolicyfororganization.OrganizationNotFoundException;
import com.clavaris.organization.application.usecase.setsessionpolicyfororganization.SetSessionPolicyForOrganizationCommand;
import com.clavaris.organization.application.usecase.setsessionpolicyfororganization.SetSessionPolicyForOrganizationUseCase;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.SessionPolicy;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
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
 * Clerk "Sessions" settings parity — the dashboard's own read+write path for an Organization's
 * session policy: {@code GET}/{@code POST
 * /platform/dashboard/organizations/{organizationId}/session-policy}. Every write here goes through
 * the exact same {@link SetSessionPolicyForOrganizationUseCase} the REST admin API ({@code
 * SetSessionPolicyController}, still {@code PlatformClient}-gated, unchanged) already used — same
 * dual-caller shape {@code PlatformRateLimitPolicyController}'s own Javadoc documents.
 *
 * <p>Ownership check: {@code organizationId} resolves through {@link
 * GetOrganizationForPlatformAccountUseCase} first (never a bare {@code organizations.existsById}
 * call, unlike the REST path) — an organizationId belonging to someone else's Organization resolves
 * identically to "doesn't exist," same anti-enumeration posture as every other dashboard
 * controller. Same HTMX-fragment-vs-redirect convention as every other dashboard mutation.
 */
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/session-policy")
public class PlatformSessionPolicyController {

  private static final String SESSION_POLICY_VIEW =
      "organization/platform/organization-session-policy";
  private static final String SESSION_POLICY_FRAGMENT = SESSION_POLICY_VIEW + " :: sessionPolicy";
  private static final String ORGANIZATION_ID_ATTRIBUTE = "organizationId";
  private static final String ORGANIZATION_NAME_ATTRIBUTE = "organizationName";
  private static final String SESSION_POLICY_ATTRIBUTE = "sessionPolicy";
  private static final String SESSION_POLICY_FORM_ATTRIBUTE = "sessionPolicyForm";
  private static final String DURATION_UNITS_ATTRIBUTE = "durationUnits";
  private static final String LIMITS_ATTRIBUTE = "limits";

  // HTMX's own request header (https://htmx.org/reference/#request_headers) — same convention as
  // every other dashboard controller's own identical constant.
  private static final String HX_REQUEST_HEADER = "HX-Request";

  private final GetOrganizationForPlatformAccountUseCase getOrganization;
  private final SetSessionPolicyForOrganizationUseCase setSessionPolicy;
  private final GetSessionPolicyForOrganizationUseCase getSessionPolicy;
  private final CurrentPlatformAccountResolver currentPlatformAccount;
  private final SessionPolicyFormValidator formValidator = new SessionPolicyFormValidator();

  public PlatformSessionPolicyController(
      final GetOrganizationForPlatformAccountUseCase getOrganization,
      final SetSessionPolicyForOrganizationUseCase setSessionPolicy,
      final GetSessionPolicyForOrganizationUseCase getSessionPolicy,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getOrganization = getOrganization;
    this.setSessionPolicy = setSessionPolicy;
    this.getSessionPolicy = getSessionPolicy;
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

    populateModel(model, organization, organizationId, false);
    return SESSION_POLICY_VIEW;
  }

  // Two exits (validation error, HTMX fragment vs. plain redirect) — same rationale as every
  // other dashboard mutation's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String set(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @ModelAttribute(SESSION_POLICY_FORM_ATTRIBUTE) final SetSessionPolicyForm form,
      final BindingResult bindingResult,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final Organization organization =
        requireOwnedOrganization(organizationId, ownerPlatformAccountId);

    // The amounts and units are checked here, in the words the page uses, not by bean validation:
    // see SessionPolicyFormValidator. On a problem the page is shown again with what was typed.
    final Optional<SessionPolicyFormValidator.Parsed> durations =
        formValidator.validate(form, bindingResult);
    if (durations.isEmpty()) {
      return rerenderWithError(request, model, organization, organizationId);
    }

    try {
      setSessionPolicy.handle(
          new SetSessionPolicyForOrganizationCommand(
              organizationId,
              durations.get().lifetime(),
              durations.get().inactivity(),
              durations.get().reverification(),
              form.isMultiSessionHandlingEnabled(),
              AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final OrganizationNotFoundException _) {
      // Not expected on this path — requireOwnedOrganization above already confirmed
      // organizationId exists and is owned — but a loud 404 is still safer than assuming that
      // guarantee can never race with a concurrent deletion, same defensive posture every other
      // dashboard mutation controller in this codebase already applies to its own "not expected"
      // catch block.
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    if (isHtmxRequest(request)) {
      populateModel(model, organization, organizationId, false);
      return SESSION_POLICY_FRAGMENT;
    }
    return "redirect:/platform/dashboard/organizations/" + organizationId + "/session-policy";
  }

  private String rerenderWithError(
      final HttpServletRequest request,
      final Model model,
      final Organization organization,
      final UUID organizationId) {
    populateModel(model, organization, organizationId, true);
    return isHtmxRequest(request) ? SESSION_POLICY_FRAGMENT : SESSION_POLICY_VIEW;
  }

  private void populateModel(
      final Model model,
      final Organization organization,
      final UUID organizationId,
      final boolean keepSubmittedForm) {
    final SessionPolicy policy = getSessionPolicy.handle(organizationId);
    model.addAttribute(ORGANIZATION_ID_ATTRIBUTE, organizationId);
    model.addAttribute(ORGANIZATION_NAME_ATTRIBUTE, organization.name());
    model.addAttribute(SESSION_POLICY_ATTRIBUTE, policy);
    model.addAttribute(DURATION_UNITS_ATTRIBUTE, List.of(DurationUnit.values()));
    model.addAttribute(LIMITS_ATTRIBUTE, SessionPolicyLimits.current());
    // After an error the form in the model is the one just submitted: keep it, so what was typed
    // is not replaced by the saved values. Otherwise show the saved policy, with each duration in
    // the largest unit that states it exactly (so 1080 minutes reads "18 hours").
    if (!keepSubmittedForm) {
      model.addAttribute(SESSION_POLICY_FORM_ATTRIBUTE, formFrom(policy));
    }
  }

  private static SetSessionPolicyForm formFrom(final SessionPolicy policy) {
    final DurationUnit.Amount lifetime = DurationUnit.bestFit(policy.maximumLifetimeMinutes());
    final DurationUnit.Amount inactivity = DurationUnit.bestFit(policy.inactivityTimeoutMinutes());
    final SetSessionPolicyForm form = new SetSessionPolicyForm();
    form.setMaximumLifetimeValue(String.valueOf(lifetime.value()));
    form.setMaximumLifetimeUnit(lifetime.unit().name());
    form.setInactivityTimeoutValue(String.valueOf(inactivity.value()));
    form.setInactivityTimeoutUnit(inactivity.unit().name());
    form.setReverificationWindowMinutes(String.valueOf(policy.reverificationWindowMinutes()));
    form.setMultiSessionHandlingEnabled(policy.multiSessionHandlingEnabled());
    return form;
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
