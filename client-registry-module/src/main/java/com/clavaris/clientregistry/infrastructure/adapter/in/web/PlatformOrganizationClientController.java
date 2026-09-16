package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.createorganizationclient.CreateOrganizationClientCommand;
import com.clavaris.clientregistry.application.usecase.createorganizationclient.CreateOrganizationClientResult;
import com.clavaris.clientregistry.application.usecase.createorganizationclient.CreateOrganizationClientUseCase;
import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationNotFoundException;
import com.clavaris.clientregistry.application.usecase.deactivateorganizationclient.DeactivateOrganizationClientCommand;
import com.clavaris.clientregistry.application.usecase.deactivateorganizationclient.DeactivateOrganizationClientUseCase;
import com.clavaris.clientregistry.application.usecase.listorganizationclientspaged.ListOrganizationClientsPagedQuery;
import com.clavaris.clientregistry.application.usecase.listorganizationclientspaged.ListOrganizationClientsPagedUseCase;
import com.clavaris.clientregistry.application.usecase.rotateorganizationclientsecret.RotateOrganizationClientSecretCommand;
import com.clavaris.clientregistry.application.usecase.rotateorganizationclientsecret.RotateOrganizationClientSecretResult;
import com.clavaris.clientregistry.application.usecase.rotateorganizationclientsecret.RotateOrganizationClientSecretUseCase;
import com.clavaris.clientregistry.domain.model.ConcurrentClientModificationException;
import com.clavaris.clientregistry.domain.model.OrganizationClient;
import com.clavaris.clientregistry.domain.model.PlatformScopes;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
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
 * ADR-0025: the dashboard's own Secret Key (ADR-0023, Clerk "Secret Key" parity) management —
 * minting, listing, deactivating, and rotating the secret of an Organization's own {@code
 * OrganizationClient}s. Every write here goes through the exact same use cases {@code
 * /api/v1/admin/**} already exposes ({@link CreateOrganizationClientUseCase}, {@link
 * DeactivateOrganizationClientUseCase}, {@link RotateOrganizationClientSecretUseCase}) — this
 * controller adds a second, session-authenticated caller, not a second implementation. See {@code
 * CreateOrganizationClientCommand}'s own Javadoc for why an {@link AuditActor#platformAccount}
 * actor on this path is a deliberate widening of an established precedent ({@code
 * CreateWorkspaceCommand}'s own), not an oversight.
 *
 * <p>{@code organizationId} resolves through {@link OrganizationForPlatformAccountResolver} — never
 * a bare repository call — so an organizationId this {@code PlatformAccount} doesn't own resolves
 * identically to "doesn't exist" (a 404), same anti-enumeration posture as organization-module's
 * own dashboard controllers. {@link DeactivateOrganizationClientCommand}/{@link
 * RotateOrganizationClientSecretCommand} both now carry that same {@code organizationId} through to
 * the use case itself (SDE-III review, 2026-09-15) — a {@code clientId} belonging to a different
 * Organization 404s from {@code DeactivateOrganizationClientService}/{@code
 * RotateOrganizationClientSecretService}'s own thrown {@link OrganizationClientNotFoundException},
 * not from a separate web-layer list-then-check this controller used to have to do itself.
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
// PMD.ExcessiveImports (TD-PERF-020's own ListOrganizationClientsPagedQuery/UseCase pushed this
// past the default threshold of 30): every import here backs a real, distinct collaborator this
// controller genuinely needs — same "wiring, not sprawl" reasoning
// OrganizationUseCaseConfig's own class-level Javadoc documents for an identical situation.
@SuppressWarnings({"PMD.LongVariable", "PMD.ExcessiveImports"})
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
  private final ListOrganizationClientsPagedUseCase listClientsPaged;
  private final DeactivateOrganizationClientUseCase deactivateClient;
  private final RotateOrganizationClientSecretUseCase rotateClientSecret;
  private final OrganizationForPlatformAccountResolver organizationResolver;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  // SDE-III review, 2026-09-15: ListOrganizationClientsUseCase dropped — it existed on this
  // controller purely to back the former requireClientIdBelongsToOrganization workaround (see
  // DeactivateOrganizationClientCommand's own Javadoc for the web-layer-only check it replaced),
  // now enforced by DeactivateOrganizationClientService/RotateOrganizationClientSecretService
  // themselves. One parameter per remaining collaborating port — same rationale as every other
  // multi-collaborator constructor in this codebase.
  public PlatformOrganizationClientController(
      final CreateOrganizationClientUseCase createClient,
      final ListOrganizationClientsPagedUseCase listClientsPaged,
      final DeactivateOrganizationClientUseCase deactivateClient,
      final RotateOrganizationClientSecretUseCase rotateClientSecret,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.createClient = createClient;
    this.listClientsPaged = listClientsPaged;
    this.deactivateClient = deactivateClient;
    this.rotateClientSecret = rotateClientSecret;
    this.organizationResolver = organizationResolver;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  // Shared by showList's own initial render and every mutation's HTMX-fragment re-render — see
  // each call site's own comment for why this exact trio (header, fresh create form, current
  // page of clients) always travels together. Placed directly after the constructor (ahead of
  // showList, unlike this method's usual position) — local pmd:cpd-check's own 75-token window
  // (pom.xml) otherwise bridges the constructor's field assignments straight into showList's own
  // near-identical delegation to DashboardControllerSupport#showPaginatedList, since both are
  // now short enough that nothing between them differs across this file and
  // PlatformOAuthClientController's own mirror. This form-type reference
  // (CreateOrganizationClientForm, not RegisterOAuthClientForm) breaks that contiguous run at a
  // real, meaningful difference instead of an arbitrary one.
  private void renderSecretKeysList(
      final Model model,
      final UUID organizationId,
      final String organizationName,
      final KeysetPageRequest pageRequest) {
    populateHeaderModel(model, organizationId, organizationName);
    model.addAttribute(CREATE_FORM_ATTRIBUTE, new CreateOrganizationClientForm());
    populateClientsModel(model, organizationId, pageRequest);
  }

  // TD-PERF-020 (keyset revision, 2026-09-14): ?after=/?before= carry an opaque KeysetCursor
  // token — see organization-module's PlatformOrganizationDashboardController for the full
  // reasoning. This GET also branches on HX-Request — a pagination link is itself an hx-get, and
  // its hx-target can't safely receive a full HTML document. SonarCloud CPD finding (CI,
  // 2026-09-14): this body byte-matched PlatformOAuthClientController#showList's own identical
  // structure across enough tokens to clear the cross-file duplication threshold — see
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
            renderSecretKeysList(model, organizationId, owned.organizationName(), pageRequest),
        new DashboardControllerSupport.PaginatedViewNames(CLIENTS_FRAGMENT, LIST_VIEW));
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
    // CPD-OFF: genuinely irreducible call-site wiring, not duplicated business logic — same
    // rationale as PlatformOAuthClientController#create's own identical marker.
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
    } catch (final IllegalArgumentException _) {
      // SDE-III review, 2026-09-15: OrganizationClient.register's own scope validation — populated
      // ALL_SCOPES_ATTRIBUTE above already excludes every PlatformScopes.OPERATOR_ONLY option, so
      // reaching this catch means the request bypassed the rendered form entirely (a raw POST, or a
      // stale/tampered form submission) — a loud 400 is correct here, not a silent 500 from
      // GlobalExceptionHandler's own catch-all.
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }

    model.addAttribute("justCreatedRawSecret", result.rawClientSecret());
    model.addAttribute("justCreatedClientId", result.organizationClient().clientId());
    renderSecretKeysList(
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
    // DeactivateOrganizationClientService itself, replacing the former web-layer-only
    // requireClientIdBelongsToOrganization workaround (see that command's own Javadoc) — a bogus
    // or cross-tenant clientId now 404s from the service's own thrown exception instead of a
    // pre-check here.
    try {
      deactivateClient.handle(
          new DeactivateOrganizationClientCommand(
              clientId,
              organizationId,
              AuditActor.platformAccount(owned.ownerPlatformAccountId())));
    } catch (final OrganizationClientNotFoundException _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final ConcurrentClientModificationException _) {
      // SDE-III review, 2026-09-15: OrganizationClient's own @Version-backed conflict — see
      // ConcurrentClientModificationException's own Javadoc for the lost-update race this closes.
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    }

    if (DashboardControllerSupport.isHtmxRequest(request)) {
      renderSecretKeysList(
          model, organizationId, owned.organizationName(), KeysetPageRequest.first());
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
    final DashboardControllerSupport.OwnedOrganization owned =
        DashboardControllerSupport.requireOwnedOrganization(
            request, organizationId, currentPlatformAccount, organizationResolver);

    // SDE-III review, 2026-09-15: same organizationId pass-through as deactivate() above.
    final RotateOrganizationClientSecretResult result;
    try {
      result =
          rotateClientSecret.handle(
              new RotateOrganizationClientSecretCommand(
                  clientId,
                  organizationId,
                  AuditActor.platformAccount(owned.ownerPlatformAccountId())));
    } catch (final OrganizationClientNotFoundException _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final ConcurrentClientModificationException _) {
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    }

    model.addAttribute("justCreatedRawSecret", result.rawSecret());
    model.addAttribute("justCreatedClientId", result.clientId());
    renderSecretKeysList(
        model, organizationId, owned.organizationName(), KeysetPageRequest.first());
    return DashboardControllerSupport.isHtmxRequest(request) ? CLIENTS_FRAGMENT : LIST_VIEW;
  }

  private void populateHeaderModel(
      final Model model, final UUID organizationId, final String organizationName) {
    model.addAttribute(ORGANIZATION_ID_ATTRIBUTE, organizationId);
    model.addAttribute(ORGANIZATION_NAME_ATTRIBUTE, organizationName);
    // SDE-III review, 2026-09-15: ORGANIZATION_CLIENT_ALLOWED, not BOOTSTRAP_DEFAULT — this
    // dashboard mints an OrganizationClient (Secret Key), which can never hold an
    // PlatformScopes.OPERATOR_ONLY scope (OrganizationClient#register enforces this structurally
    // regardless of what this form submits); narrowing the choices here means the operator-only
    // scopes are never even offered, instead of being offered and then rejected after submit.
    model.addAttribute(ALL_SCOPES_ATTRIBUTE, PlatformScopes.ORGANIZATION_CLIENT_ALLOWED);
  }

  private void populateClientsModel(
      final Model model, final UUID organizationId, final KeysetPageRequest pageRequest) {
    final KeysetPage<OrganizationClient> clientsPage =
        listClientsPaged.handle(new ListOrganizationClientsPagedQuery(organizationId, pageRequest));
    model.addAttribute("clients", clientsPage.content());
    model.addAttribute("clientsPage", clientsPage);
  }
}
