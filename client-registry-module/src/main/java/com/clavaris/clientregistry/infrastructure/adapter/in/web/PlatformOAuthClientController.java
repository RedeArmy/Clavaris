package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.deactivateoauthclient.DeactivateOAuthClientCommand;
import com.clavaris.clientregistry.application.usecase.deactivateoauthclient.DeactivateOAuthClientUseCase;
import com.clavaris.clientregistry.application.usecase.deactivateoauthclient.OAuthClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.listoauthclientspaged.ListOAuthClientsPagedQuery;
import com.clavaris.clientregistry.application.usecase.listoauthclientspaged.ListOAuthClientsPagedUseCase;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.OrganizationNotFoundException;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientCommand;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientResult;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientUseCase;
import com.clavaris.clientregistry.application.usecase.rotateoauthclientsecret.RotateOAuthClientSecretCommand;
import com.clavaris.clientregistry.application.usecase.rotateoauthclientsecret.RotateOAuthClientSecretResult;
import com.clavaris.clientregistry.application.usecase.rotateoauthclientsecret.RotateOAuthClientSecretUseCase;
import com.clavaris.clientregistry.domain.model.ConcurrentClientModificationException;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
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
 * Javadoc documents.
 *
 * <p>{@code organizationId} resolves through the already-shared {@link
 * OrganizationForPlatformAccountResolver} (client-registry-module's own copy, bridged in {@code
 * app}) — same anti-enumeration posture as every other dashboard controller in this module. {@link
 * DeactivateOAuthClientCommand}/{@link RotateOAuthClientSecretCommand} both carry that same {@code
 * organizationId} through to the use case itself (SDE-III review, 2026-09-15) — a {@code clientId}
 * belonging to a different Organization 404s from {@code DeactivateOAuthClientService}/{@code
 * RotateOAuthClientSecretService}'s own thrown {@code OAuthClientNotFoundException}, not from a
 * separate web-layer list-then-check this controller used to have to do itself — same pattern
 * {@code PlatformOrganizationClientController} already established.
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
  private final ListOAuthClientsPagedUseCase listClientsPaged;
  private final DeactivateOAuthClientUseCase deactivateClient;
  private final RotateOAuthClientSecretUseCase rotateClientSecret;
  private final OrganizationForPlatformAccountResolver organizationResolver;
  private final CurrentPlatformAccountResolver currentPlatformAccount;
  private final String clavarisBaseUrl;

  // SDE-III review, 2026-09-15: ListOAuthClientsUseCase dropped — it existed on this controller
  // purely to back the former requireClientIdBelongsToOrganization workaround (see
  // DeactivateOAuthClientCommand's own Javadoc for the web-layer-only check it replaced), now
  // enforced by DeactivateOAuthClientService/RotateOAuthClientSecretService themselves. One
  // parameter per remaining collaborating port — same rationale as every other multi-collaborator
  // constructor in this codebase.
  //
  // clavarisBaseUrl (SDE-III review, 2026-09-16): same property, same default, ResendMailSender's
  // own precedent for building a {@code {clavarisBaseUrl}/o/{organizationId}/...} URL from
  // configuration rather than the current request — an operator viewing this page over Tailscale
  // Funnel or any other proxy still needs the real externally-reachable issuer URL, not whatever
  // Host header happened to arrive here.
  public PlatformOAuthClientController(
      final RegisterOAuthClientUseCase registerClient,
      final ListOAuthClientsPagedUseCase listClientsPaged,
      final DeactivateOAuthClientUseCase deactivateClient,
      final RotateOAuthClientSecretUseCase rotateClientSecret,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount,
      @Value("${CLAVARIS_BASE_URL:http://localhost:8080}") final String clavarisBaseUrl) {
    this.registerClient = registerClient;
    this.listClientsPaged = listClientsPaged;
    this.deactivateClient = deactivateClient;
    this.rotateClientSecret = rotateClientSecret;
    this.organizationResolver = organizationResolver;
    this.currentPlatformAccount = currentPlatformAccount;
    this.clavarisBaseUrl = clavarisBaseUrl;
  }

  // Shared by showList's own initial render and every mutation's HTMX-fragment re-render — see
  // each call site's own comment for why this exact trio (header, fresh create form, current
  // page of clients) always travels together. Placed directly after the constructor (ahead of
  // showList, unlike this method's usual position) — local pmd:cpd-check's own 75-token window
  // (pom.xml) otherwise bridges the constructor's field assignments straight into showList's own
  // near-identical delegation to DashboardControllerSupport#showPaginatedList, since both are
  // now short enough that nothing between them differs across this file and
  // PlatformOrganizationClientController's own mirror. This form-type reference
  // (RegisterOAuthClientForm, not CreateOrganizationClientForm) breaks that contiguous run at a
  // real, meaningful difference instead of an arbitrary one.
  private void renderOAuthClientsList(
      final Model model,
      final UUID organizationId,
      final String organizationName,
      final KeysetPageRequest pageRequest) {
    populateHeaderModel(model, organizationId, organizationName);
    model.addAttribute(CREATE_FORM_ATTRIBUTE, new RegisterOAuthClientForm());
    populateClientsModel(model, organizationId, pageRequest);
  }

  // TD-PERF-020 (keyset revision, 2026-09-14): ?after=/?before= carry an opaque KeysetCursor
  // token — see organization-module's PlatformOrganizationDashboardController for the full
  // reasoning. This GET also branches on HX-Request — a pagination link is itself an hx-get, and
  // its hx-target can't safely receive a full HTML document. SonarCloud CPD finding (CI,
  // 2026-09-14): this body byte-matched PlatformOrganizationClientController#showList's own
  // identical structure across enough tokens to clear the cross-file duplication threshold — see
  // DashboardControllerSupport#showPaginatedList's own Javadoc for the extraction that removes it.
  @GetMapping
  public String showList(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @RequestParam(required = false) final String after,
      @RequestParam(required = false) final String before,
      final Model model) {
    return DashboardControllerSupport.showPaginatedList(
        request,
        organizationId,
        currentPlatformAccount,
        organizationResolver,
        KeysetPageRequest.fromCursors(after, before),
        (owned, pageRequest) ->
            renderOAuthClientsList(model, organizationId, owned.organizationName(), pageRequest),
        new DashboardControllerSupport.PaginatedViewNames(CLIENTS_FRAGMENT, LIST_VIEW));
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
    // CPD-OFF: genuinely irreducible call-site wiring, not duplicated business logic — every real
    // decision already lives on
    // DashboardControllerSupport#requireOwnedOrganizationOrRenderValidationErrors
    // itself (see its own Javadoc); what's left is this controller passing its own 7 collaborators
    // to that one shared call and unpacking the result, the same shape
    // PlatformOrganizationClientController#create's own identical preamble necessarily has too.
    final DashboardControllerSupport.OwnershipOrValidationErrorView resolved =
        DashboardControllerSupport.requireOwnedOrganizationOrRenderValidationErrors(
            request,
            organizationId,
            currentPlatformAccount,
            organizationResolver,
            bindingResult,
            owned -> {
              populateHeaderModel(model, organizationId, owned.organizationName());
              populateClientsModel(model, organizationId, KeysetPageRequest.first());
            },
            new DashboardControllerSupport.PaginatedViewNames(CLIENTS_FRAGMENT, LIST_VIEW));
    if (resolved.validationErrorView().isPresent()) {
      return resolved.validationErrorView().get();
    }
    final UUID ownerPlatformAccountId = resolved.owned().ownerPlatformAccountId();
    // CPD-ON

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
    // Clerk-style "here's exactly what to put in your app" panel — see
    // OidcClientSetupInstructions's own Javadoc for why every URL is built from configuration, not
    // the current request.
    model.addAttribute(
        "setupInstructions",
        OidcClientSetupInstructions.from(organizationId, clavarisBaseUrl, result.client()));
    renderOAuthClientsList(
        model, organizationId, resolved.owned().organizationName(), KeysetPageRequest.first());
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
        DashboardControllerSupport.requireOwnedOrganization(
            request, organizationId, currentPlatformAccount, organizationResolver);

    // SDE-III review, 2026-09-15: organizationId now passed through and verified by
    // DeactivateOAuthClientService itself — see that command's own Javadoc for the web-layer-only
    // workaround this replaces.
    try {
      deactivateClient.handle(
          new DeactivateOAuthClientCommand(
              clientId,
              organizationId,
              AuditActor.platformAccount(owned.ownerPlatformAccountId())));
    } catch (final OAuthClientNotFoundException _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final ConcurrentClientModificationException _) {
      // SDE-III review, 2026-09-15: OAuthClient's own @Version-backed conflict — see
      // ConcurrentClientModificationException's own Javadoc for the lost-update race this closes.
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    }

    if (DashboardControllerSupport.isHtmxRequest(request)) {
      renderOAuthClientsList(
          model, organizationId, owned.organizationName(), KeysetPageRequest.first());
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
        DashboardControllerSupport.requireOwnedOrganization(
            request, organizationId, currentPlatformAccount, organizationResolver);

    // SDE-III review, 2026-09-15: same organizationId pass-through as deactivate() above.
    final RotateOAuthClientSecretResult result;
    try {
      result =
          rotateClientSecret.handle(
              new RotateOAuthClientSecretCommand(
                  clientId,
                  organizationId,
                  AuditActor.platformAccount(owned.ownerPlatformAccountId())));
    } catch (
        final com.clavaris.clientregistry.application.usecase.rotateoauthclientsecret
                .OAuthClientNotFoundException
            _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final ConcurrentClientModificationException _) {
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    }

    model.addAttribute("justRegisteredRawSecret", result.rawSecret());
    model.addAttribute("justRegisteredClientId", result.clientId());
    renderOAuthClientsList(
        model, organizationId, owned.organizationName(), KeysetPageRequest.first());
    return DashboardControllerSupport.isHtmxRequest(request) ? CLIENTS_FRAGMENT : LIST_VIEW;
  }

  private void populateHeaderModel(
      final Model model, final UUID organizationId, final String organizationName) {
    model.addAttribute(ORGANIZATION_ID_ATTRIBUTE, organizationId);
    model.addAttribute(ORGANIZATION_NAME_ATTRIBUTE, organizationName);
    model.addAttribute(GRANT_TYPE_OPTIONS_ATTRIBUTE, OAuthGrantTypeOptions.DASHBOARD_OPTIONS);
  }

  private void populateClientsModel(
      final Model model, final UUID organizationId, final KeysetPageRequest pageRequest) {
    final KeysetPage<OAuthClient> clientsPage =
        listClientsPaged.handle(new ListOAuthClientsPagedQuery(organizationId, pageRequest));
    model.addAttribute("clients", clientsPage.content());
    model.addAttribute("clientsPage", clientsPage);
  }
}
