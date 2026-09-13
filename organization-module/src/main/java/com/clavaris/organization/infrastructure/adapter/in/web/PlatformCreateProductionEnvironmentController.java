package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.createproductionenvironment.CreateProductionEnvironmentCommand;
import com.clavaris.organization.application.usecase.createproductionenvironment.CreateProductionEnvironmentResult;
import com.clavaris.organization.application.usecase.createproductionenvironment.CreateProductionEnvironmentUseCase;
import com.clavaris.organization.application.usecase.createproductionenvironment.OrganizationAlreadyHasLinkedEnvironmentException;
import com.clavaris.organization.application.usecase.createproductionenvironment.OrganizationNotDevelopmentException;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountQuery;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
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
 * ADR-0025, TD-FUT-032: the dashboard's own "promote to production" — the Organization-detail
 * page's Danger Zone. Reuses the exact same {@link CreateProductionEnvironmentUseCase} the REST
 * admin API already exposes, with a second, session-authenticated {@link
 * AuditActor#platformAccount} caller — see {@code CreateProductionEnvironmentCommand}'s own Javadoc
 * for why this widening needed no restriction to correct, unlike {@code
 * DeleteOrganizationCommand}'s own sibling correction.
 *
 * <p>Unlike delete, this action is non-destructive — it creates a brand-new sibling row and copies
 * nothing, never destroys the source Organization's own data — so a single form submission is
 * proportionate; no two-step "type to confirm" flow. A single-page form, not an HTMX fragment:
 * success navigates to a genuinely different page (the newly created PRODUCTION Organization's own
 * detail page), which HTMX's own in-place-fragment-swap convention every other dashboard mutation
 * in this codebase uses isn't the right shape for.
 *
 * <p>{@code organizationId} resolves through {@link GetOrganizationForPlatformAccountUseCase} —
 * same anti-enumeration posture every other dashboard controller in this codebase already
 * establishes.
 */
// PMD.LongVariable: createProductionEnvironment/currentPlatformAccount/ownerPlatformAccountId are
// long by design, not accidentally — same class-level-suppression precedent
// PlatformOrganizationDetailController's own identical rationale documents.
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/promote-to-production")
public class PlatformCreateProductionEnvironmentController {

  private static final String FORM_VIEW = "organization/platform/promote-to-production";
  private static final String FORM_ATTRIBUTE = "promoteForm";
  private static final String ORGANIZATION_ATTRIBUTE = "organization";

  private final GetOrganizationForPlatformAccountUseCase getOrganization;
  private final CreateProductionEnvironmentUseCase createProductionEnvironment;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  public PlatformCreateProductionEnvironmentController(
      final GetOrganizationForPlatformAccountUseCase getOrganization,
      final CreateProductionEnvironmentUseCase createProductionEnvironment,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getOrganization = getOrganization;
    this.createProductionEnvironment = createProductionEnvironment;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  @GetMapping
  public String showForm(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final Organization organization =
        requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
    model.addAttribute(FORM_ATTRIBUTE, new CreateProductionEnvironmentForm());
    return FORM_VIEW;
  }

  // PMD.OnlyOneReturn: validation-error/business-error/success each need their own exit, same
  // rationale as every other create-shaped handler in this codebase.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String create(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute(FORM_ATTRIBUTE) final CreateProductionEnvironmentForm form,
      final BindingResult bindingResult,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final Organization organization =
        requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    if (bindingResult.hasErrors()) {
      model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
      return FORM_VIEW;
    }

    final CreateProductionEnvironmentResult result;
    try {
      result =
          createProductionEnvironment.handle(
              new CreateProductionEnvironmentCommand(
                  organizationId,
                  form.getName(),
                  AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final OrganizationNotDevelopmentException
        | OrganizationAlreadyHasLinkedEnvironmentException _) {
      // TD-FUT-032: surfaced as a form error, same "a human filling out this form needs to see
      // why," not a bare 409, posture UnsafeWebhookUrlException's own handling already establishes
      // for an identical situation.
      model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
      model.addAttribute(FORM_ATTRIBUTE, form);
      model.addAttribute("notEligibleError", true);
      return FORM_VIEW;
    }

    return "redirect:/platform/dashboard/organizations/" + result.organization().id();
  }

  private Organization requireOwnedOrganization(
      final UUID organizationId, final UUID ownerPlatformAccountId) {
    return getOrganization
        .handle(new GetOrganizationForPlatformAccountQuery(organizationId, ownerPlatformAccountId))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private UUID requireCurrentPlatformAccount(final HttpServletRequest request) {
    return currentPlatformAccount
        .resolve(request)
        .orElseThrow(
            () -> new IllegalStateException("No authenticated PlatformAccount on this request"));
  }
}
