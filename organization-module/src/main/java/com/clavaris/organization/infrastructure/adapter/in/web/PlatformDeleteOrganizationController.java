package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.deleteorganization.DeleteOrganizationCommand;
import com.clavaris.organization.application.usecase.deleteorganization.DeleteOrganizationUseCase;
import com.clavaris.organization.application.usecase.deleteorganization.OrganizationNotFoundException;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * ADR-0025, TD-FUT-032, BR-DATA-02/03: the dashboard's own Danger Zone — hard-deletes an entire
 * Organization, self-service (see {@code DeleteOrganizationCommand}'s own corrected Javadoc). The
 * single most destructive action this dashboard exposes, so — unlike every other mutation in this
 * codebase, all of them single-click — this is a genuine two-step flow, a first for this codebase:
 *
 * <ol>
 *   <li><b>GET</b> — renders a confirmation page. Issues a fresh, single-use, short-TTL server-side
 *       token ({@link DeleteOrganizationConfirmationTokens#issue}) into the session, embedded as a
 *       hidden field alongside the ordinary {@code _csrf} one every form in this codebase already
 *       carries.
 *   <li><b>POST</b> — requires BOTH: the submitted confirmation token to match and still be
 *       unexpired ({@link DeleteOrganizationConfirmationTokens#consume}, itself always single-use
 *       regardless of outcome), AND the operator to have typed the Organization's own current
 *       {@code name} exactly. The ordinary {@code _csrf} token alone defends a normal POST against
 *       a cross-site forged request, but not against a same-site, single-click, no-extra-friction
 *       resubmission (a stale page, a browser back-button replay, a same-origin script that already
 *       has DOM access to the ambient CSRF value) — the confirmation token specifically defeats
 *       that, since it's invalidated the instant it's checked, valid or not. The typed-name field
 *       is a human-confidence guard only, not a security control: {@code Organization.name} is
 *       deliberately not unique (see its own migration comment) — the actual delete target always
 *       comes from the already-ownership-checked path variable, never re-derived from the typed
 *       string.
 * </ol>
 *
 * <p>Every other section on {@code organization-detail.html} is either read-only or reversible;
 * this is neither. Confirmed with the user before building (CLAUDE.md §12): the two real,
 * independent bugs {@code DeleteOrganizationService} itself needed fixing first (a linked
 * DEVELOPMENT/PRODUCTION pair's own FK-violation risk; orphaned webhook-module data) are fixed
 * there directly, not worked around here — see that class's own Javadoc.
 */
// PMD.LongVariable: deleteOrganization/currentPlatformAccount/ownerPlatformAccountId/
// CONFIRMATION_TOKEN_PARAM are long by design, not accidentally — same class-level-suppression
// precedent PlatformOrganizationDetailController's own identical rationale documents.
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/delete")
public class PlatformDeleteOrganizationController {

  private static final String CONFIRM_VIEW = "organization/platform/delete-organization-confirm";
  private static final String CONFIRMATION_TOKEN_PARAM = "confirmationToken";

  private final GetOrganizationForPlatformAccountUseCase getOrganization;
  private final DeleteOrganizationUseCase deleteOrganization;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  public PlatformDeleteOrganizationController(
      final GetOrganizationForPlatformAccountUseCase getOrganization,
      final DeleteOrganizationUseCase deleteOrganization,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getOrganization = getOrganization;
    this.deleteOrganization = deleteOrganization;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  @GetMapping
  public String showConfirmation(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final Organization organization =
        requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    populateConfirmModel(request, model, organization);
    return CONFIRM_VIEW;
  }

  // PMD.OnlyOneReturn: token-invalid/name-mismatch/success each need their own exit, same
  // rationale as every other multi-outcome handler in this codebase.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String delete(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @RequestParam(CONFIRMATION_TOKEN_PARAM) final String confirmationToken,
      @RequestParam("confirmedName") final String confirmedName,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final Organization organization =
        requireOwnedOrganization(organizationId, ownerPlatformAccountId);

    // Single-use — this call always invalidates the token, whether it matches or not, before
    // anything else below runs. See this class's own Javadoc.
    final boolean tokenValid =
        DeleteOrganizationConfirmationTokens.consume(request, organizationId, confirmationToken);
    if (!tokenValid) {
      populateConfirmModel(request, model, organization);
      model.addAttribute("confirmationExpiredError", true);
      return CONFIRM_VIEW;
    }
    if (!organization.name().equals(confirmedName)) {
      populateConfirmModel(request, model, organization);
      model.addAttribute("nameMismatchError", true);
      return CONFIRM_VIEW;
    }

    try {
      deleteOrganization.handle(
          new DeleteOrganizationCommand(
              organizationId, AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final OrganizationNotFoundException _) {
      // Not expected on this path — requireOwnedOrganization above already confirmed
      // organizationId exists — but a loud 404 is still safer than assuming that can never race
      // with a concurrent deletion.
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    // The Organization's own detail page this action was reached from no longer exists — back to
    // the list, same "redirect to the list after a destructive action" shape
    // PlatformOrganizationDashboardController's own create() already establishes for its own
    // plain (non-HTMX) success path.
    return "redirect:/platform/dashboard";
  }

  private void populateConfirmModel(
      final HttpServletRequest request, final Model model, final Organization organization) {
    model.addAttribute("organization", organization);
    // Always a fresh token — on the very first GET, and again on any failed POST retry (whose own
    // token was already consumed above), same single-use guarantee either way.
    model.addAttribute(
        CONFIRMATION_TOKEN_PARAM,
        DeleteOrganizationConfirmationTokens.issue(request, organization.id()));
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
