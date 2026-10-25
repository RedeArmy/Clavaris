package com.clavaris.organization.infrastructure.adapter.in.web;

import static com.clavaris.organization.infrastructure.adapter.in.web.WorkspaceDashboardSupport.ORGANIZATIONS_REDIRECT_PREFIX;
import static com.clavaris.organization.infrastructure.adapter.in.web.WorkspaceDashboardSupport.ORGANIZATION_ATTRIBUTE;
import static com.clavaris.organization.infrastructure.adapter.in.web.WorkspaceDashboardSupport.isHtmxRequest;
import static com.clavaris.organization.infrastructure.adapter.in.web.WorkspaceTeamsModel.WORKSPACE_DETAIL_VIEW;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.organization.application.usecase.createworkspace.CreateWorkspaceCommand;
import com.clavaris.organization.application.usecase.createworkspace.CreateWorkspaceUseCase;
import com.clavaris.organization.application.usecase.deleteworkspace.DeleteWorkspaceCommand;
import com.clavaris.organization.application.usecase.deleteworkspace.DeleteWorkspaceUseCase;
import com.clavaris.organization.application.usecase.listworkspacesfororganizationpaged.ListWorkspacesForOrganizationPagedQuery;
import com.clavaris.organization.application.usecase.listworkspacesfororganizationpaged.ListWorkspacesForOrganizationPagedUseCase;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.Workspace;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * ADR-0025: creating, deleting and opening a Workspace from the Organization-detail page. Every
 * write goes through the same use cases {@code /api/v1/admin/**} exposes ({@link
 * CreateWorkspaceUseCase}, {@link DeleteWorkspaceUseCase}) — this controller adds a second,
 * session-authenticated caller, not a second implementation. See {@code CreateWorkspaceCommand}'s
 * own Javadoc for why an {@link AuditActor#platformAccount} actor on this path is a deliberate
 * widening of an established precedent ({@link PlatformOrganizationDashboardController}'s own
 * {@code create}), not an oversight.
 *
 * <p>This used to be one 1,200-line controller for the whole Workspace surface; it is now split by
 * resource, each class sharing {@link WorkspaceDashboardSupport} (ownership checks, so a
 * workspaceId belonging to an Organization this {@code PlatformAccount} does not own resolves
 * exactly like "does not exist": a 404, same anti-enumeration posture as {@link
 * PlatformOrganizationDetailController}) and {@link WorkspaceTeamsModel} (the Teams &amp; Roles
 * read model):
 *
 * <ul>
 *   <li>this class: Workspaces themselves;
 *   <li>{@link PlatformWorkspaceTeamController}: Teams (create, rename, delete, add roles);
 *   <li>{@link PlatformWorkspaceTeamRoleController}: creating and deleting a role from a Team;
 *   <li>{@link PlatformWorkspaceHierarchyController}: the Team &rarr; Role &rarr; Members tab and
 *       its "Assign role"/"Remove" actions.
 * </ul>
 *
 * <p>Same HTMX-fragment-vs-redirect branching as {@link PlatformOrganizationDashboardController}:
 * an {@code HX-Request} gets just the relevant fragment; a plain form submit keeps a full {@code
 * redirect:} on success — HTMX is progressive enhancement, every action works with JavaScript
 * disabled.
 */
// PMD.LongVariable: collaborator names spell out the use case they hold.
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/workspaces")
public class PlatformWorkspaceController {

  private static final String ORGANIZATION_DETAIL_VIEW =
      "organization/platform/organization-detail";
  private static final String WORKSPACES_FRAGMENT = ORGANIZATION_DETAIL_VIEW + " :: workspaces";
  private static final String WORKSPACE_FORM_ATTRIBUTE = "workspaceForm";

  private final WorkspaceDashboardSupport access;
  private final WorkspaceTeamsModel teamsModel;
  private final ListWorkspacesForOrganizationPagedUseCase listWorkspaces;
  private final CreateWorkspaceUseCase createWorkspace;
  private final DeleteWorkspaceUseCase deleteWorkspaceUseCase;

  public PlatformWorkspaceController(
      final WorkspaceDashboardSupport access,
      final WorkspaceTeamsModel teamsModel,
      final ListWorkspacesForOrganizationPagedUseCase listWorkspaces,
      final CreateWorkspaceUseCase createWorkspace,
      final DeleteWorkspaceUseCase deleteWorkspaceUseCase) {
    this.access = access;
    this.teamsModel = teamsModel;
    this.listWorkspaces = listWorkspaces;
    this.createWorkspace = createWorkspace;
    this.deleteWorkspaceUseCase = deleteWorkspaceUseCase;
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String create(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute(WORKSPACE_FORM_ATTRIBUTE) final CreateWorkspaceForm form,
      final BindingResult bindingResult,
      final Model model) {
    final UUID ownerPlatformAccountId = access.requireCurrentPlatformAccount(request);
    final Organization organization =
        access.requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    if (bindingResult.hasErrors()) {
      // A plain (non-HTMX) validation error re-renders the WHOLE organization-detail page — same
      // reason "organization"/"workspaces" are populated here too, not just on
      // PlatformOrganizationDetailController's own GET.
      model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
      addWorkspacesToModel(model, organizationId, KeysetPageRequest.first());
      return isHtmxRequest(request) ? WORKSPACES_FRAGMENT : ORGANIZATION_DETAIL_VIEW;
    }

    // TD-SEC-007: same self-service posture as CreateOrganizationCommand's own dashboard caller —
    // organizationId was just resolved through requireOwnedOrganization above, so this
    // PlatformAccount genuinely owns the Organization it's adding a Workspace to.
    createWorkspace.handle(
        new CreateWorkspaceCommand(
            organizationId, form.getName(), AuditActor.platformAccount(ownerPlatformAccountId)));

    if (isHtmxRequest(request)) {
      model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
      // The first page (no cursor) — a newly-created Workspace sorts first (newest-first
      // ordering), same reasoning PlatformOrganizationDashboardController's own identical
      // create() already documents.
      addWorkspacesToModel(model, organizationId, KeysetPageRequest.first());
      model.addAttribute(WORKSPACE_FORM_ATTRIBUTE, new CreateWorkspaceForm());
      return WORKSPACES_FRAGMENT;
    }
    return ORGANIZATIONS_REDIRECT_PREFIX + organizationId;
  }

  private void addWorkspacesToModel(
      final Model model, final UUID organizationId, final KeysetPageRequest pageRequest) {
    final KeysetPage<Workspace> workspacesPage =
        listWorkspaces.handle(
            new ListWorkspacesForOrganizationPagedQuery(organizationId, pageRequest));
    model.addAttribute("workspaces", workspacesPage.content());
    model.addAttribute("workspacesPage", workspacesPage);
  }

  // Live UX request, 2026-09-28: "Delete" next to "View" on the Workspaces list, gated behind a
  // popup that states the deletion is permanent and requires typing the Workspace's own current
  // name — same "typed-name is a human-confidence guard only, not a security control" posture
  // PlatformDeleteOrganizationController's own identical check documents (the real delete target
  // always comes from the already-ownership-checked workspaceId path variable, never re-derived
  // from confirmedName). Deliberately NOT that class's own single-use-confirmation-token flow:
  // that ceremony is reserved for Organization deletion's strictly larger blast radius (an entire
  // tenant's account pool), documented there as "the single most destructive action this
  // dashboard exposes" — this is a popup, not a dedicated confirm page, matching every other
  // dialog-gated mutation already on this dashboard.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/{workspaceId}/delete")
  public String deleteWorkspace(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID workspaceId,
      @RequestParam("confirmedName") final String confirmedName,
      final Model model) {
    final UUID ownerPlatformAccountId = access.requireCurrentPlatformAccount(request);
    final Organization organization =
        access.requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = access.requireOwnedWorkspace(organizationId, workspaceId);

    if (!workspace.name().equals(confirmedName)) {
      model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
      addWorkspacesToModel(model, organizationId, KeysetPageRequest.first());
      model.addAttribute(WORKSPACE_FORM_ATTRIBUTE, new CreateWorkspaceForm());
      // Which row's own dialog should reopen with the error — see organization-detail.html's own
      // per-row th:if on this attribute.
      model.addAttribute("deleteWorkspaceMismatchId", workspaceId);
      return isHtmxRequest(request) ? WORKSPACES_FRAGMENT : ORGANIZATION_DETAIL_VIEW;
    }

    deleteWorkspaceUseCase.handle(
        new DeleteWorkspaceCommand(
            workspaceId, AuditActor.platformAccount(ownerPlatformAccountId)));

    if (isHtmxRequest(request)) {
      model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
      addWorkspacesToModel(model, organizationId, KeysetPageRequest.first());
      // The fragment's own "Create workspace" form at the bottom is th:object-bound to this
      // attribute (th:field="*{name}") — every other HTMX-returning caller of WORKSPACES_FRAGMENT
      // (create()'s own htmx branch) sets it too; a real bug, caught by
      // htmxDeleteWorkspacePostReturnsTheWorkspacesFragment, not hypothetical.
      model.addAttribute(WORKSPACE_FORM_ATTRIBUTE, new CreateWorkspaceForm());
      return WORKSPACES_FRAGMENT;
    }
    return ORGANIZATIONS_REDIRECT_PREFIX + organizationId;
  }

  @GetMapping("/{workspaceId}")
  public String showDetail(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID workspaceId,
      final Model model) {
    final UUID ownerPlatformAccountId = access.requireCurrentPlatformAccount(request);
    final Organization organization =
        access.requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = access.requireOwnedWorkspace(organizationId, workspaceId);
    model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
    teamsModel.populateTeamsModel(model, workspace);
    return WORKSPACE_DETAIL_VIEW;
  }
}
