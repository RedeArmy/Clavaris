package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.deactivateoauthclient.DeactivateOAuthClientCommand;
import com.clavaris.clientregistry.application.usecase.deactivateoauthclient.DeactivateOAuthClientUseCase;
import com.clavaris.clientregistry.application.usecase.listoauthclients.ListOAuthClientsUseCase;
import com.clavaris.clientregistry.application.usecase.listoauthclientspaged.ListOAuthClientsPagedQuery;
import com.clavaris.clientregistry.application.usecase.listoauthclientspaged.ListOAuthClientsPagedUseCase;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.OrganizationNotFoundException;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientCommand;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientResult;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientUseCase;
import com.clavaris.clientregistry.application.usecase.rotateoauthclientsecret.RotateOAuthClientSecretCommand;
import com.clavaris.clientregistry.application.usecase.rotateoauthclientsecret.RotateOAuthClientSecretResult;
import com.clavaris.clientregistry.application.usecase.rotateoauthclientsecret.RotateOAuthClientSecretUseCase;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.domain.model.Page;
import com.clavaris.common.domain.model.PageRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * ADR-0025: the dashboard's own real {@code OAuthClient} registration/listing/deactivation/
 * secret-rotation — the end-user OIDC login client registration TD-FUT-032 named as the genuine
 * remaining gap once Secret Key management (a different concept, {@code OrganizationClient})
 * shipped. See {@code PlatformOrganizationClientController}'s own Javadoc for the general shape
 * this controller mirrors; the differences below are deliberate, not oversights.
 *
 * <p>Every write here goes through the exact same use cases the REST admin API already exposes
 * ({@link RegisterOAuthClientUseCase} — {@code POST
 * /api/v1/admin/organizations/{organizationId}/clients}) plus two genuinely new ones this increment
 * adds, {@link DeactivateOAuthClientUseCase} and {@link RotateOAuthClientSecretUseCase} (SDE-III
 * review, 2026-09-11: {@code OAuthClient} itself gained an {@code active} flag and a {@code
 * rotateSecret(...)} method, closing the domain-layer gap an earlier pass of this controller had
 * deliberately left open — see {@code OAuthClient#deactivate}/{@code OAuthClient#rotateSecret}'s
 * own Javadoc). Every write here — create included — carries a second, session-authenticated {@link
 * AuditActor#platformAccount} caller, same widening {@code RegisterOAuthClientCommand}'s own
 * Javadoc documents. {@link ListOAuthClientsUseCase} and the {@code findAllByOrganizationId} method
 * it depends on are new — see that use case's own Javadoc.
 *
 * <p>{@code organizationId} resolves through the already-shared {@link
 * OrganizationForPlatformAccountResolver} (client-registry-module's own copy, bridged in {@code
 * app}) — same anti-enumeration posture as every other dashboard controller in this module. Neither
 * {@link DeactivateOAuthClientCommand} nor {@link RotateOAuthClientSecretCommand} carries an {@code
 * organizationId} of its own (both key off the client's own {@code clientId} string, same shape as
 * their {@code OrganizationClient} siblings) — this controller resolves the target {@link
 * OAuthClient} via the already-organizationId-scoped {@link ListOAuthClientsUseCase} first, so a
 * {@code clientId} belonging to a different Organization 404s before either mutating use case is
 * ever called, not after — same pattern {@code PlatformOrganizationClientController} already
 * established.
 *
 * <p>Unlike {@code PlatformOrganizationClientController}'s own deactivate, deactivation here still
 * keeps the usual redirect-on-success shape (no secret involved); create and rotate-secret never
 * return {@code "redirect:"} even for a plain (non-HTMX) form submit — {@link
 * RegisterOAuthClientResult#rawClientSecret()}/{@link RotateOAuthClientSecretResult#rawSecret()}
 * are shown exactly once and have nowhere safe to travel through a redirect (never a URL query
 * string, browser history, or server access log).
 */
// PMD.ExcessiveImports (TD-PERF-020's own ListOAuthClientsPagedQuery/UseCase pushed this past the
// default threshold of 30): every import here backs a real, distinct collaborator this controller
// genuinely needs — same "wiring, not sprawl" reasoning OrganizationUseCaseConfig's own
// class-level Javadoc documents for an identical situation.
@SuppressWarnings({"PMD.LongVariable", "PMD.ExcessiveImports"})
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/oauth-clients")
public class PlatformOAuthClientController {

  private static final String LIST_VIEW = "clientregistry/platform/organization-oauth-clients";
  private static final String CLIENTS_FRAGMENT = LIST_VIEW + " :: clients";
  private static final String CREATE_FORM_ATTRIBUTE = "createForm";
  private static final String ORGANIZATION_ID_ATTRIBUTE = "organizationId";
  private static final String ORGANIZATION_NAME_ATTRIBUTE = "organizationName";
  private static final String GRANT_TYPE_OPTIONS_ATTRIBUTE = "grantTypeOptions";

  private final RegisterOAuthClientUseCase registerClient;
  private final ListOAuthClientsUseCase listClients;
  private final ListOAuthClientsPagedUseCase listClientsPaged;
  private final DeactivateOAuthClientUseCase deactivateClient;
  private final RotateOAuthClientSecretUseCase rotateClientSecret;
  private final OrganizationForPlatformAccountResolver organizationResolver;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  @SuppressWarnings("java:S107") // one parameter per collaborating port — same rationale as every
  // other multi-collaborator constructor in this codebase.
  public PlatformOAuthClientController(
      final RegisterOAuthClientUseCase registerClient,
      final ListOAuthClientsUseCase listClients,
      final ListOAuthClientsPagedUseCase listClientsPaged,
      final DeactivateOAuthClientUseCase deactivateClient,
      final RotateOAuthClientSecretUseCase rotateClientSecret,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.registerClient = registerClient;
    this.listClients = listClients;
    this.listClientsPaged = listClientsPaged;
    this.deactivateClient = deactivateClient;
    this.rotateClientSecret = rotateClientSecret;
    this.organizationResolver = organizationResolver;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  // TD-PERF-020: page is 0-indexed — see organization-module's
  // PlatformOrganizationDashboardController for the full reasoning. This GET also branches on
  // HX-Request — a pagination link is itself an hx-get, and its hx-target can't safely receive a
  // full HTML document.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @GetMapping
  public String showList(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @RequestParam(defaultValue = "0") final int page,
      final Model model) {
    final DashboardControllerSupport.OwnedOrganization owned =
        DashboardControllerSupport.requireOwnedOrganization(
            request, organizationId, currentPlatformAccount, organizationResolver);
    renderOAuthClientsList(model, organizationId, owned.organizationName(), page);
    if (DashboardControllerSupport.isHtmxRequest(request)) {
      return CLIENTS_FRAGMENT;
    }
    return LIST_VIEW;
  }

  // Shared by showList's own initial render and every mutation's HTMX-fragment re-render — see
  // each call site's own comment for why this exact trio (header, fresh create form, current
  // page of clients) always travels together.
  private void renderOAuthClientsList(
      final Model model, final UUID organizationId, final String organizationName, final int page) {
    populateHeaderModel(model, organizationId, organizationName);
    model.addAttribute(CREATE_FORM_ATTRIBUTE, new RegisterOAuthClientForm());
    populateClientsModel(model, organizationId, page);
  }

  // Never returns "redirect:" — see this class's own Javadoc for why a one-time secret can't
  // safely travel through one. PMD.OnlyOneReturn: create/error each need their own exit, same
  // rationale as PlatformOrganizationClientController#create's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String create(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute(CREATE_FORM_ATTRIBUTE) final RegisterOAuthClientForm form,
      final BindingResult bindingResult,
      final Model model) {
    final DashboardControllerSupport.OwnedOrganization owned =
        DashboardControllerSupport.requireOwnedOrganization(
            request, organizationId, currentPlatformAccount, organizationResolver);
    final UUID ownerPlatformAccountId = owned.ownerPlatformAccountId();

    if (bindingResult.hasErrors()) {
      populateHeaderModel(model, organizationId, owned.organizationName());
      populateClientsModel(model, organizationId, 0);
      return DashboardControllerSupport.isHtmxRequest(request) ? CLIENTS_FRAGMENT : LIST_VIEW;
    }

    final RegisterOAuthClientResult result;
    try {
      result =
          registerClient.handle(
              new RegisterOAuthClientCommand(
                  organizationId,
                  form.parseRedirectUris(),
                  form.getAllowedGrantTypes(),
                  form.parseAllowedScopes(),
                  form.isRequireConsent(),
                  form.parsePostLogoutRedirectUris(),
                  AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final OrganizationNotFoundException _) {
      // Not expected on this path — requireOwnedOrganizationName above already confirmed
      // organizationId exists — but a loud 404 is still safer than assuming that can never race
      // with a concurrent deletion.
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    model.addAttribute("justRegisteredRawSecret", result.rawClientSecret());
    model.addAttribute("justRegisteredClientId", result.client().clientId());
    renderOAuthClientsList(model, organizationId, owned.organizationName(), 0);
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
    final DashboardControllerSupport.OwnedOrganization owned =
        requireOwnedOAuthClient(request, organizationId, clientId);

    deactivateClient.handle(
        new DeactivateOAuthClientCommand(
            clientId, AuditActor.platformAccount(owned.ownerPlatformAccountId())));

    if (DashboardControllerSupport.isHtmxRequest(request)) {
      renderOAuthClientsList(model, organizationId, owned.organizationName(), 0);
      return CLIENTS_FRAGMENT;
    }
    return "redirect:/platform/dashboard/organizations/" + organizationId + "/oauth-clients";
  }

  // Never returns "redirect:" — same rationale as create() above.
  @PostMapping("/{clientId}/rotate-secret")
  public String rotateSecret(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final String clientId,
      final Model model) {
    final DashboardControllerSupport.OwnedOrganization owned =
        requireOwnedOAuthClient(request, organizationId, clientId);

    final RotateOAuthClientSecretResult result =
        rotateClientSecret.handle(
            new RotateOAuthClientSecretCommand(
                clientId, AuditActor.platformAccount(owned.ownerPlatformAccountId())));

    model.addAttribute("justRegisteredRawSecret", result.rawSecret());
    model.addAttribute("justRegisteredClientId", result.clientId());
    renderOAuthClientsList(model, organizationId, owned.organizationName(), 0);
    return DashboardControllerSupport.isHtmxRequest(request) ? CLIENTS_FRAGMENT : LIST_VIEW;
  }

  // deactivate()/rotateSecret() both need "who owns this Organization, and does clientId
  // actually belong to it" before touching anything — SonarCloud-flagged intra-class
  // duplication (2026-09-13) once both call sites landed with the identical 6-line preamble.
  private DashboardControllerSupport.OwnedOrganization requireOwnedOAuthClient(
      final HttpServletRequest request, final UUID organizationId, final String clientId) {
    final DashboardControllerSupport.OwnedOrganization owned =
        DashboardControllerSupport.requireOwnedOrganization(
            request, organizationId, currentPlatformAccount, organizationResolver);
    DashboardControllerSupport.requireClientIdBelongsToOrganization(
        listClients.handle(organizationId).stream().map(OAuthClient::clientId).toList(), clientId);
    return owned;
  }

  private void populateHeaderModel(
      final Model model, final UUID organizationId, final String organizationName) {
    model.addAttribute(ORGANIZATION_ID_ATTRIBUTE, organizationId);
    model.addAttribute(ORGANIZATION_NAME_ATTRIBUTE, organizationName);
    model.addAttribute(GRANT_TYPE_OPTIONS_ATTRIBUTE, OAuthGrantTypeOptions.DASHBOARD_OPTIONS);
  }

  private void populateClientsModel(final Model model, final UUID organizationId, final int page) {
    final Page<OAuthClient> clientsPage =
        listClientsPaged.handle(
            new ListOAuthClientsPagedQuery(
                organizationId, new PageRequest(page, PageRequest.DEFAULT_SIZE)));
    model.addAttribute("clients", clientsPage.content());
    model.addAttribute("clientsPage", clientsPage);
  }
}
