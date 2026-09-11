package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.createorganizationclient.CreateOrganizationClientCommand;
import com.clavaris.clientregistry.application.usecase.createorganizationclient.CreateOrganizationClientResult;
import com.clavaris.clientregistry.application.usecase.createorganizationclient.CreateOrganizationClientUseCase;
import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationNotFoundException;
import com.clavaris.clientregistry.application.usecase.deactivateorganizationclient.DeactivateOrganizationClientCommand;
import com.clavaris.clientregistry.application.usecase.deactivateorganizationclient.DeactivateOrganizationClientUseCase;
import com.clavaris.clientregistry.application.usecase.listorganizationclients.ListOrganizationClientsUseCase;
import com.clavaris.clientregistry.application.usecase.rotateorganizationclientsecret.RotateOrganizationClientSecretCommand;
import com.clavaris.clientregistry.application.usecase.rotateorganizationclientsecret.RotateOrganizationClientSecretResult;
import com.clavaris.clientregistry.application.usecase.rotateorganizationclientsecret.RotateOrganizationClientSecretUseCase;
import com.clavaris.clientregistry.domain.model.OrganizationClient;
import com.clavaris.clientregistry.domain.model.PlatformScopes;
import com.clavaris.common.domain.model.AuditActor;
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
 * ADR-0025: the dashboard's own Secret Key (ADR-0023, Clerk "Secret Key" parity) management —
 * minting, listing, deactivating, and rotating the secret of an Organization's own {@code
 * OrganizationClient}s. Every write here goes through the exact same use cases {@code
 * /api/v1/admin/**} already exposes ({@link CreateOrganizationClientUseCase}, {@link
 * DeactivateOrganizationClientUseCase}, {@link RotateOrganizationClientSecretUseCase}) plus the
 * already-scoped {@link ListOrganizationClientsUseCase} — this controller adds a second,
 * session-authenticated caller, not a second implementation. See {@code
 * CreateOrganizationClientCommand}'s own Javadoc for why an {@link AuditActor#platformAccount}
 * actor on this path is a deliberate widening of an established precedent ({@code
 * CreateWorkspaceCommand}'s own), not an oversight.
 *
 * <p>{@code organizationId} resolves through {@link OrganizationForPlatformAccountResolver} — never
 * a bare repository call — so an organizationId this {@code PlatformAccount} doesn't own resolves
 * identically to "doesn't exist" (a 404), same anti-enumeration posture as organization-module's
 * own dashboard controllers. Neither {@link DeactivateOrganizationClientCommand} nor {@link
 * RotateOrganizationClientSecretCommand} carries an {@code organizationId} of its own (both key off
 * the client's own {@code clientId} string) — this controller resolves the target {@link
 * OrganizationClient} via the already-organizationId-scoped {@link ListOrganizationClientsUseCase}
 * first, so a {@code clientId} belonging to a different Organization 404s before either mutating
 * use case is ever called, not after.
 *
 * <p>Unlike every other dashboard controller in this codebase, a successful create/rotate never
 * redirects, even for a plain (non-HTMX) form submit: {@link
 * CreateOrganizationClientResult#rawClientSecret()}/ {@link
 * RotateOrganizationClientSecretResult#rawSecret()} are shown exactly once, at the moment of
 * creation/rotation — a {@code redirect:} would have nowhere safe to carry that value (never a URL
 * query string, browser history, or server access log) and would simply lose it. Both render the
 * list page directly (200), same HTMX-fragment-vs-full-page branching as every other action here,
 * just without the redirect half of that choice. Deactivation carries no secret, so it keeps the
 * usual redirect-on-success shape.
 */
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/secret-keys")
public class PlatformOrganizationClientController {

  private static final String LIST_VIEW = "clientregistry/platform/organization-secret-keys";
  private static final String CLIENTS_FRAGMENT = LIST_VIEW + " :: clients";
  private static final String CREATE_FORM_ATTRIBUTE = "createForm";
  private static final String ORGANIZATION_ID_ATTRIBUTE = "organizationId";
  private static final String ORGANIZATION_NAME_ATTRIBUTE = "organizationName";
  private static final String ALL_SCOPES_ATTRIBUTE = "allScopes";

  private final CreateOrganizationClientUseCase createClient;
  private final ListOrganizationClientsUseCase listClients;
  private final DeactivateOrganizationClientUseCase deactivateClient;
  private final RotateOrganizationClientSecretUseCase rotateClientSecret;
  private final OrganizationForPlatformAccountResolver organizationResolver;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  public PlatformOrganizationClientController(
      final CreateOrganizationClientUseCase createClient,
      final ListOrganizationClientsUseCase listClients,
      final DeactivateOrganizationClientUseCase deactivateClient,
      final RotateOrganizationClientSecretUseCase rotateClientSecret,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.createClient = createClient;
    this.listClients = listClients;
    this.deactivateClient = deactivateClient;
    this.rotateClientSecret = rotateClientSecret;
    this.organizationResolver = organizationResolver;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  @GetMapping
  public String showList(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      final Model model) {
    final UUID ownerPlatformAccountId =
        DashboardControllerSupport.requireCurrentPlatformAccount(request, currentPlatformAccount);
    final String organizationName =
        DashboardControllerSupport.requireOwnedOrganizationName(
            organizationId, ownerPlatformAccountId, organizationResolver);
    populateHeaderModel(model, organizationId, organizationName);
    model.addAttribute(CREATE_FORM_ATTRIBUTE, new CreateOrganizationClientForm());
    populateClientsModel(model, organizationId);
    return LIST_VIEW;
  }

  // Never returns "redirect:" — see this class's own Javadoc for why a one-time secret can't
  // safely travel through one. PMD.OnlyOneReturn: create/error each need their own exit, same
  // rationale as every other handler in this codebase.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String create(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute(CREATE_FORM_ATTRIBUTE) final CreateOrganizationClientForm form,
      final BindingResult bindingResult,
      final Model model) {
    final UUID ownerPlatformAccountId =
        DashboardControllerSupport.requireCurrentPlatformAccount(request, currentPlatformAccount);
    final String organizationName =
        DashboardControllerSupport.requireOwnedOrganizationName(
            organizationId, ownerPlatformAccountId, organizationResolver);
    populateHeaderModel(model, organizationId, organizationName);

    if (bindingResult.hasErrors()) {
      populateClientsModel(model, organizationId);
      return DashboardControllerSupport.isHtmxRequest(request) ? CLIENTS_FRAGMENT : LIST_VIEW;
    }

    final CreateOrganizationClientResult result;
    try {
      result =
          createClient.handle(
              new CreateOrganizationClientCommand(
                  organizationId,
                  form.getAllowedScopes(),
                  AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final OrganizationNotFoundException _) {
      // Not expected on this path — requireOwnedOrganizationName above already confirmed
      // organizationId exists — but a loud 404 is still safer than assuming that can never race
      // with a concurrent deletion.
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    model.addAttribute("justCreatedRawSecret", result.rawClientSecret());
    model.addAttribute("justCreatedClientId", result.organizationClient().clientId());
    model.addAttribute(CREATE_FORM_ATTRIBUTE, new CreateOrganizationClientForm());
    populateClientsModel(model, organizationId);
    return DashboardControllerSupport.isHtmxRequest(request) ? CLIENTS_FRAGMENT : LIST_VIEW;
  }

  // Two exits (HTMX fragment vs. plain redirect) — same rationale as every other dashboard
  // controller's own identical "after a mutation succeeds" suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/{clientId}/deactivate")
  public String deactivate(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final String clientId,
      final Model model) {
    final UUID ownerPlatformAccountId =
        DashboardControllerSupport.requireCurrentPlatformAccount(request, currentPlatformAccount);
    final String organizationName =
        DashboardControllerSupport.requireOwnedOrganizationName(
            organizationId, ownerPlatformAccountId, organizationResolver);
    DashboardControllerSupport.requireClientIdBelongsToOrganization(
        listClients.handle(organizationId).stream().map(OrganizationClient::clientId).toList(),
        clientId);

    deactivateClient.handle(
        new DeactivateOrganizationClientCommand(
            clientId, AuditActor.platformAccount(ownerPlatformAccountId)));

    if (DashboardControllerSupport.isHtmxRequest(request)) {
      populateHeaderModel(model, organizationId, organizationName);
      model.addAttribute(CREATE_FORM_ATTRIBUTE, new CreateOrganizationClientForm());
      populateClientsModel(model, organizationId);
      return CLIENTS_FRAGMENT;
    }
    return "redirect:/platform/dashboard/organizations/" + organizationId + "/secret-keys";
  }

  // Never returns "redirect:" — same rationale as create() above.
  @PostMapping("/{clientId}/rotate-secret")
  public String rotateSecret(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final String clientId,
      final Model model) {
    final UUID ownerPlatformAccountId =
        DashboardControllerSupport.requireCurrentPlatformAccount(request, currentPlatformAccount);
    final String organizationName =
        DashboardControllerSupport.requireOwnedOrganizationName(
            organizationId, ownerPlatformAccountId, organizationResolver);
    DashboardControllerSupport.requireClientIdBelongsToOrganization(
        listClients.handle(organizationId).stream().map(OrganizationClient::clientId).toList(),
        clientId);

    final RotateOrganizationClientSecretResult result =
        rotateClientSecret.handle(
            new RotateOrganizationClientSecretCommand(
                clientId, AuditActor.platformAccount(ownerPlatformAccountId)));

    populateHeaderModel(model, organizationId, organizationName);
    model.addAttribute("justCreatedRawSecret", result.rawSecret());
    model.addAttribute("justCreatedClientId", result.clientId());
    model.addAttribute(CREATE_FORM_ATTRIBUTE, new CreateOrganizationClientForm());
    populateClientsModel(model, organizationId);
    return DashboardControllerSupport.isHtmxRequest(request) ? CLIENTS_FRAGMENT : LIST_VIEW;
  }

  private void populateHeaderModel(
      final Model model, final UUID organizationId, final String organizationName) {
    model.addAttribute(ORGANIZATION_ID_ATTRIBUTE, organizationId);
    model.addAttribute(ORGANIZATION_NAME_ATTRIBUTE, organizationName);
    model.addAttribute(ALL_SCOPES_ATTRIBUTE, PlatformScopes.BOOTSTRAP_DEFAULT);
  }

  private void populateClientsModel(final Model model, final UUID organizationId) {
    model.addAttribute("clients", listClients.handle(organizationId));
  }
}
