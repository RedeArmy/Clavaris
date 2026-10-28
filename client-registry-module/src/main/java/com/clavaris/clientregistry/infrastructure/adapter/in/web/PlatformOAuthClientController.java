package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.activateoauthclient.ActivateOAuthClientCommand;
import com.clavaris.clientregistry.application.usecase.activateoauthclient.ActivateOAuthClientResult;
import com.clavaris.clientregistry.application.usecase.activateoauthclient.ActivateOAuthClientUseCase;
import com.clavaris.clientregistry.application.usecase.deactivateoauthclient.DeactivateOAuthClientCommand;
import com.clavaris.clientregistry.application.usecase.deactivateoauthclient.DeactivateOAuthClientUseCase;
import com.clavaris.clientregistry.application.usecase.deactivateoauthclient.OAuthClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.listoauthclientspaged.ListOAuthClientsPagedQuery;
import com.clavaris.clientregistry.application.usecase.listoauthclientspaged.ListOAuthClientsPagedUseCase;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientDefaults;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.OrganizationNotFoundException;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientCommand;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientResult;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientUseCase;
import com.clavaris.clientregistry.application.usecase.rotateoauthclientsecret.RotateOAuthClientSecretCommand;
import com.clavaris.clientregistry.application.usecase.rotateoauthclientsecret.RotateOAuthClientSecretResult;
import com.clavaris.clientregistry.application.usecase.rotateoauthclientsecret.RotateOAuthClientSecretUseCase;
import com.clavaris.clientregistry.application.usecase.updateoauthclientredirectsettings.UpdateOAuthClientRedirectSettingsCommand;
import com.clavaris.clientregistry.application.usecase.updateoauthclientredirectsettings.UpdateOAuthClientRedirectSettingsUseCase;
import com.clavaris.clientregistry.domain.model.ConcurrentClientModificationException;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
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
 * ADR-0025, BR-ORG-06 (SDE-III refactor, 2026-09-23, Clerk-parity master-detail redesign): the
 * dashboard's own real {@code OAuthClient} listing/detail/deactivation/secret-rotation/redirect-
 * settings pages. Every {@code OAuthClient} an Organization owns is now auto-provisioned with
 * {@link OAuthClientDefaults}' fixed grant types/scopes/consent setting — this controller no longer
 * exposes a form for any of the three; {@code redirectUris}/{@code postLogoutRedirectUris} (via
 * {@link UpdateOAuthClientRedirectSettingsUseCase}) are the only fields an owner may ever change,
 * from the detail page.
 *
 * <p>"Add client" (POST {@code /oauth-clients}, no request body — there is nothing left for the
 * caller to supply) always renders the full {@link #DETAIL_VIEW}, deliberately never the list or an
 * HTMX fragment scoped to the list's own {@code #clients-content} — Clerk's own equivalent lands
 * the caller directly on the new credential's own settings page, not back on the list, and the raw
 * secret shown exactly once here has nowhere safe to travel through a redirect (never a URL query
 * string, browser history, or server access log) — same reasoning {@link #rotateSecret} below
 * already applied under the old single-page design.
 *
 * <p>{@code organizationId} resolves through the already-shared {@link
 * OrganizationForPlatformAccountResolver}; {@link DeactivateOAuthClientCommand}/{@link
 * RotateOAuthClientSecretCommand}/{@link UpdateOAuthClientRedirectSettingsCommand} all carry that
 * same {@code organizationId} through to their own use case (a {@code clientId} belonging to a
 * different Organization 404s from the use case's own thrown {@code OAuthClientNotFoundException},
 * never from a separate web-layer check).
 */
// PMD.AvoidFieldNameMatchingMethodName: updateRedirectSettings (the field) and its same-named
// @PostMapping handler method name the same real concept — same "the field is the collaborator,
// the method is the endpoint that calls it" shape every other controller in this codebase already
// has for its own use-case fields (e.g. PlatformAccountProfileAdminController's own identical
// suppression). PMD.TooManyMethods: the four add/remove-row endpoints (live UX request,
// 2026-09-24 — a real list UI, not a textarea) plus activate() and the three Configuration-card
// mutators (updateGrantTypes/updateScopes/updateConsent, also 2026-09-24) are each a genuinely
// distinct HTTP mapping, not sprawl — same "wiring, not sprawl" reasoning
// OrganizationUseCaseConfig's own class-level suppression documents for an identical situation.
// The alternative (splitting this into several controllers per sub-resource) would scatter the
// ownership-check/populateDetailModel plumbing every one of these endpoints shares, not remove any
// real complexity. PMD.ExcessiveParameterList/CouplingBetweenObjects: one constructor parameter
// per collaborating use case, same rationale as every other multi-collaborator constructor in
// this codebase (see e.g. RegisterOAuthClientService's own java:S107 precedent) — a synthetic
// parameter object here would add indirection without removing complexity, and this class's own
// job (route every Configuration-card mutation to its own use case) structurally requires knowing
// about all of them. PMD.CyclomaticComplexity (class total): the sum of every endpoint's own
// already-justified per-method complexity — a controller
// routing eleven genuinely distinct HTTP mappings to their own use cases inherently sums to a
// large total; each individual method stays within its own documented, reasonable complexity.
@SuppressWarnings({"PMD.LongVariable", "PMD.ExcessiveImports"})
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/oauth-clients")
public class PlatformOAuthClientController {

  private static final String LIST_VIEW = "clientregistry/platform/organization-oauth-clients";
  private static final String CLIENTS_FRAGMENT = LIST_VIEW + " :: clients";
  private static final String DETAIL_VIEW =
      "clientregistry/platform/organization-oauth-client-detail";
  private static final String DETAIL_FRAGMENT = DETAIL_VIEW + " :: detail";
  private static final String ORGANIZATION_ID_ATTRIBUTE = "organizationId";
  private static final String ORGANIZATION_NAME_ATTRIBUTE = "organizationName";
  private static final String JUST_REGISTERED_RAW_SECRET_ATTRIBUTE = "justRegisteredRawSecret";
  private static final String JUST_REGISTERED_CLIENT_ID_ATTRIBUTE = "justRegisteredClientId";

  private final RegisterOAuthClientUseCase registerClient;
  private final ListOAuthClientsPagedUseCase listClientsPaged;
  private final DeactivateOAuthClientUseCase deactivateClient;
  private final ActivateOAuthClientUseCase activateClient;
  private final RotateOAuthClientSecretUseCase rotateClientSecret;
  private final PlatformOAuthClientSupport support;

  public PlatformOAuthClientController(
      final RegisterOAuthClientUseCase registerClient,
      final ListOAuthClientsPagedUseCase listClientsPaged,
      final DeactivateOAuthClientUseCase deactivateClient,
      final ActivateOAuthClientUseCase activateClient,
      final RotateOAuthClientSecretUseCase rotateClientSecret,
      final PlatformOAuthClientSupport support) {
    this.registerClient = registerClient;
    this.listClientsPaged = listClientsPaged;
    this.deactivateClient = deactivateClient;
    this.activateClient = activateClient;
    this.rotateClientSecret = rotateClientSecret;
    this.support = support;
  }

  private void renderOAuthClientsList(
      final Model model,
      final UUID organizationId,
      final String organizationName,
      final KeysetPageRequest pageRequest) {
    model.addAttribute(ORGANIZATION_ID_ATTRIBUTE, organizationId);
    model.addAttribute(ORGANIZATION_NAME_ATTRIBUTE, organizationName);
    final KeysetPage<OAuthClient> clientsPage =
        listClientsPaged.handle(new ListOAuthClientsPagedQuery(organizationId, pageRequest));
    model.addAttribute("clients", clientsPage.content());
    model.addAttribute("clientsPage", clientsPage);
  }

  // TD-PERF-020 (keyset revision, 2026-09-14): ?after=/?before= carry an opaque KeysetCursor
  // token — see organization-module's PlatformOrganizationDashboardController for the full
  // reasoning.
  @GetMapping
  public String showList(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @RequestParam(required = false) final String after,
      @RequestParam(required = false) final String before,
      final Model model) {
    return support.showPaginatedList(
        request,
        organizationId,
        KeysetPageRequest.fromCursors(after, before),
        (owned, pageRequest) ->
            renderOAuthClientsList(model, organizationId, owned.organizationName(), pageRequest),
        new DashboardControllerSupport.PaginatedViewNames(CLIENTS_FRAGMENT, LIST_VIEW));
  }

  @GetMapping("/{clientId}")
  public String showDetail(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final String clientId,
      final Model model) {
    final DashboardControllerSupport.OwnedOrganization owned =
        support.requireOwnedOrganization(request, organizationId);
    final OAuthClient client = support.requireOwnedClient(organizationId, clientId);
    support.populateDetailModel(model, organizationId, owned.organizationName(), client);
    return DashboardControllerSupport.isHtmxRequest(request) ? DETAIL_FRAGMENT : DETAIL_VIEW;
  }

  // Always DETAIL_VIEW, never CLIENTS_FRAGMENT/LIST_VIEW or an HTMX branch — see this class's own
  // Javadoc for why.
  @PostMapping
  public String create(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      final Model model) {
    final DashboardControllerSupport.OwnedOrganization owned =
        support.requireOwnedOrganization(request, organizationId);

    final RegisterOAuthClientResult result;
    try {
      result =
          registerClient.handle(
              new RegisterOAuthClientCommand(
                  organizationId,
                  List.of(),
                  OAuthClientDefaults.GRANT_TYPES,
                  OAuthClientDefaults.SCOPES,
                  OAuthClientDefaults.REQUIRE_CONSENT,
                  List.of(),
                  AuditActor.platformAccount(owned.ownerPlatformAccountId())));
    } catch (final OrganizationNotFoundException _) {
      // Not expected on this path — requireOwnedOrganization above already confirmed
      // organizationId exists — but a loud 404 is still safer than assuming that can never race
      // with a concurrent deletion.
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    support.populateDetailModel(model, organizationId, owned.organizationName(), result.client());
    model.addAttribute(JUST_REGISTERED_RAW_SECRET_ATTRIBUTE, result.rawClientSecret());
    model.addAttribute(JUST_REGISTERED_CLIENT_ID_ATTRIBUTE, result.client().clientId());
    return DETAIL_VIEW;
  }

  // Two exits (HTMX fragment vs. plain redirect) — same rationale as every other dashboard
  // controller's own identical "after a mutation succeeds" suppression. Redirects to the
  // detail page now, not the list — no secret involved in deactivation, so a redirect is safe.
  @PostMapping("/{clientId}/deactivate")
  public String deactivate(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final String clientId,
      final Model model) {
    final DashboardControllerSupport.OwnedOrganization owned =
        support.requireOwnedOrganization(request, organizationId);

    try {
      deactivateClient.handle(
          new DeactivateOAuthClientCommand(
              clientId,
              organizationId,
              AuditActor.platformAccount(owned.ownerPlatformAccountId())));
    } catch (final OAuthClientNotFoundException _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final ConcurrentClientModificationException _) {
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    }

    if (DashboardControllerSupport.isHtmxRequest(request)) {
      support.populateDetailModel(
          model,
          organizationId,
          owned.organizationName(),
          support.requireOwnedClient(organizationId, clientId));
      return DETAIL_FRAGMENT;
    }
    return PlatformOAuthClientSupport.redirectToDetail(organizationId, clientId);
  }

  // Live UX request, 2026-09-24: reactivates a previously deactivated client. Live UX request,
  // 2026-09-25: also rotates its secret (see ActivateOAuthClientResult's own Javadoc) — never
  // returns "redirect:", same rationale as create()/rotateSecret(): the raw secret shown exactly
  // once here has nowhere safe to travel through a redirect.
  @PostMapping("/{clientId}/activate")
  public String activate(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final String clientId,
      final Model model) {
    final DashboardControllerSupport.OwnedOrganization owned =
        support.requireOwnedOrganization(request, organizationId);

    final ActivateOAuthClientResult result;
    try {
      result =
          activateClient.handle(
              new ActivateOAuthClientCommand(
                  clientId,
                  organizationId,
                  AuditActor.platformAccount(owned.ownerPlatformAccountId())));
    } catch (
        final com.clavaris.clientregistry.application.usecase.activateoauthclient
                .OAuthClientNotFoundException
            _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final ConcurrentClientModificationException _) {
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    }

    support.populateDetailModel(
        model,
        organizationId,
        owned.organizationName(),
        support.requireOwnedClient(organizationId, clientId));
    model.addAttribute(JUST_REGISTERED_RAW_SECRET_ATTRIBUTE, result.rawSecret());
    model.addAttribute(JUST_REGISTERED_CLIENT_ID_ATTRIBUTE, result.clientId());
    return DashboardControllerSupport.isHtmxRequest(request) ? DETAIL_FRAGMENT : DETAIL_VIEW;
  }

  // Never returns "redirect:" — same rationale as create() above.
  @PostMapping("/{clientId}/rotate-secret")
  public String rotateSecret(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final String clientId,
      final Model model) {
    final DashboardControllerSupport.OwnedOrganization owned =
        support.requireOwnedOrganization(request, organizationId);

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

    support.populateDetailModel(
        model,
        organizationId,
        owned.organizationName(),
        support.requireOwnedClient(organizationId, clientId));
    model.addAttribute(JUST_REGISTERED_RAW_SECRET_ATTRIBUTE, result.rawSecret());
    model.addAttribute(JUST_REGISTERED_CLIENT_ID_ATTRIBUTE, result.clientId());
    return DashboardControllerSupport.isHtmxRequest(request) ? DETAIL_FRAGMENT : DETAIL_VIEW;
  }
}
