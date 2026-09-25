package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.activateoauthclient.ActivateOAuthClientCommand;
import com.clavaris.clientregistry.application.usecase.activateoauthclient.ActivateOAuthClientResult;
import com.clavaris.clientregistry.application.usecase.activateoauthclient.ActivateOAuthClientUseCase;
import com.clavaris.clientregistry.application.usecase.deactivateoauthclient.DeactivateOAuthClientCommand;
import com.clavaris.clientregistry.application.usecase.deactivateoauthclient.DeactivateOAuthClientUseCase;
import com.clavaris.clientregistry.application.usecase.deactivateoauthclient.OAuthClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.getoauthclientfororganization.GetOAuthClientForOrganizationQuery;
import com.clavaris.clientregistry.application.usecase.getoauthclientfororganization.GetOAuthClientForOrganizationUseCase;
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
import com.clavaris.clientregistry.application.usecase.updateoauthclientconsent.UpdateOAuthClientConsentCommand;
import com.clavaris.clientregistry.application.usecase.updateoauthclientconsent.UpdateOAuthClientConsentUseCase;
import com.clavaris.clientregistry.application.usecase.updateoauthclientgranttypes.UpdateOAuthClientGrantTypesCommand;
import com.clavaris.clientregistry.application.usecase.updateoauthclientgranttypes.UpdateOAuthClientGrantTypesUseCase;
import com.clavaris.clientregistry.application.usecase.updateoauthclientredirectsettings.OAuthClientInactiveException;
import com.clavaris.clientregistry.application.usecase.updateoauthclientredirectsettings.UpdateOAuthClientRedirectSettingsCommand;
import com.clavaris.clientregistry.application.usecase.updateoauthclientredirectsettings.UpdateOAuthClientRedirectSettingsUseCase;
import com.clavaris.clientregistry.application.usecase.updateoauthclientscopes.UpdateOAuthClientScopesCommand;
import com.clavaris.clientregistry.application.usecase.updateoauthclientscopes.UpdateOAuthClientScopesUseCase;
import com.clavaris.clientregistry.domain.model.ConcurrentClientModificationException;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
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
// about all of them. PMD.AvoidDuplicateLiterals: "PMD.OnlyOneReturn" as a repeated
// @SuppressWarnings value across several genuinely-multi-exit handler methods — extracting it to
// a named constant would not make any of those methods clearer. PMD.CyclomaticComplexity (class
// total): the sum of every endpoint's own already-justified per-method complexity — a controller
// routing eleven genuinely distinct HTTP mappings to their own use cases inherently sums to a
// large total; each individual method stays within its own documented, reasonable complexity.
@SuppressWarnings({
  "PMD.LongVariable",
  "PMD.ExcessiveImports",
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.TooManyMethods",
  "PMD.ExcessiveParameterList",
  "PMD.CouplingBetweenObjects",
  "PMD.AvoidDuplicateLiterals",
  "PMD.CyclomaticComplexity"
})
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/oauth-clients")
public class PlatformOAuthClientController {

  private static final String LIST_VIEW = "clientregistry/platform/organization-oauth-clients";
  private static final String CLIENTS_FRAGMENT = LIST_VIEW + " :: clients";
  private static final String DETAIL_VIEW =
      "clientregistry/platform/organization-oauth-client-detail";
  private static final String DETAIL_FRAGMENT = DETAIL_VIEW + " :: detail";
  private static final String REDIRECT_SETTINGS_FORM_ATTRIBUTE = "redirectSettingsForm";
  private static final String REDIRECT_URIS_ERROR_ATTRIBUTE = "redirectUrisError";
  private static final String ORGANIZATION_ID_ATTRIBUTE = "organizationId";
  private static final String ORGANIZATION_NAME_ATTRIBUTE = "organizationName";
  private static final String INACTIVE_CLIENT_ERROR = "This OAuthClient is inactive.";
  private static final String JUST_REGISTERED_RAW_SECRET_ATTRIBUTE = "justRegisteredRawSecret";
  private static final String JUST_REGISTERED_CLIENT_ID_ATTRIBUTE = "justRegisteredClientId";

  private final RegisterOAuthClientUseCase registerClient;
  private final ListOAuthClientsPagedUseCase listClientsPaged;
  private final GetOAuthClientForOrganizationUseCase getClient;
  private final DeactivateOAuthClientUseCase deactivateClient;
  private final ActivateOAuthClientUseCase activateClient;
  private final RotateOAuthClientSecretUseCase rotateClientSecret;
  private final UpdateOAuthClientRedirectSettingsUseCase updateRedirectSettings;
  private final UpdateOAuthClientGrantTypesUseCase updateGrantTypes;
  private final UpdateOAuthClientScopesUseCase updateScopes;
  private final UpdateOAuthClientConsentUseCase updateConsent;
  private final OrganizationForPlatformAccountResolver organizationResolver;
  private final CurrentPlatformAccountResolver currentPlatformAccount;
  private final String clavarisBaseUrl;

  // One parameter per collaborating port — same rationale as every other multi-collaborator
  // constructor in this codebase. clavarisBaseUrl: same property/default/reasoning as this
  // class's own pre-existing Javadoc already documented (ResendMailSender's own precedent for an
  // externally-reachable URL, not whatever Host header happened to arrive here).
  @SuppressWarnings("java:S107")
  public PlatformOAuthClientController(
      final RegisterOAuthClientUseCase registerClient,
      final ListOAuthClientsPagedUseCase listClientsPaged,
      final GetOAuthClientForOrganizationUseCase getClient,
      final DeactivateOAuthClientUseCase deactivateClient,
      final ActivateOAuthClientUseCase activateClient,
      final RotateOAuthClientSecretUseCase rotateClientSecret,
      final UpdateOAuthClientRedirectSettingsUseCase updateRedirectSettings,
      final UpdateOAuthClientGrantTypesUseCase updateGrantTypes,
      final UpdateOAuthClientScopesUseCase updateScopes,
      final UpdateOAuthClientConsentUseCase updateConsent,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount,
      @Value("${CLAVARIS_BASE_URL:http://localhost:8080}") final String clavarisBaseUrl) {
    this.registerClient = registerClient;
    this.listClientsPaged = listClientsPaged;
    this.getClient = getClient;
    this.deactivateClient = deactivateClient;
    this.activateClient = activateClient;
    this.rotateClientSecret = rotateClientSecret;
    this.updateRedirectSettings = updateRedirectSettings;
    this.updateGrantTypes = updateGrantTypes;
    this.updateScopes = updateScopes;
    this.updateConsent = updateConsent;
    this.organizationResolver = organizationResolver;
    this.currentPlatformAccount = currentPlatformAccount;
    this.clavarisBaseUrl = clavarisBaseUrl;
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

  // Shared by every one of this controller's own detail-page renders (GET, create, rotate-secret,
  // deactivate's own HTMX branch) — organizationId/organizationName/client/its own redirect-
  // settings form (pre-filled from the client's current lists)/its setup-instructions panel always
  // travel together.
  private void populateDetailModel(
      final Model model,
      final UUID organizationId,
      final String organizationName,
      final OAuthClient client) {
    populateDetailModel(
        model,
        organizationId,
        organizationName,
        client,
        UpdateOAuthClientRedirectSettingsForm.from(
            client.redirectUris(), client.postLogoutRedirectUris()));
  }

  // Live UX request, 2026-09-24: the add/remove-row endpoints below need to re-render this same
  // model but with a caller-supplied, still-unsaved form (the in-progress row list) instead of
  // one freshly rebuilt from the persisted client — this overload is what makes that possible
  // without duplicating the other four attributes here too.
  private void populateDetailModel(
      final Model model,
      final UUID organizationId,
      final String organizationName,
      final OAuthClient client,
      final UpdateOAuthClientRedirectSettingsForm redirectSettingsForm) {
    model.addAttribute(ORGANIZATION_ID_ATTRIBUTE, organizationId);
    model.addAttribute(ORGANIZATION_NAME_ATTRIBUTE, organizationName);
    model.addAttribute("client", client);
    model.addAttribute(REDIRECT_SETTINGS_FORM_ATTRIBUTE, redirectSettingsForm);
    // Clerk-style "here's exactly what to put in your app" panel — see
    // OidcClientSetupInstructions's own Javadoc for where each value comes from. Shown
    // permanently on the detail page now, not just right after registration.
    model.addAttribute(
        "setupInstructions",
        OidcClientSetupInstructions.from(organizationId, clavarisBaseUrl, client));
  }

  private OAuthClient requireOwnedClient(final UUID organizationId, final String clientId) {
    return getClient
        .handle(new GetOAuthClientForOrganizationQuery(clientId, organizationId))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  // Shared by every plain (non-HTMX) success exit on this controller — six call sites as of
  // 2026-09-24 (deactivate/activate/redirect-settings/grant-types/scopes/consent), extracted once
  // real duplication set in, not preemptively.
  private static String redirectToDetail(final UUID organizationId, final String clientId) {
    return "redirect:/platform/dashboard/organizations/"
        + organizationId
        + "/oauth-clients/"
        + clientId;
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

  @GetMapping("/{clientId}")
  public String showDetail(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final String clientId,
      final Model model) {
    final DashboardControllerSupport.OwnedOrganization owned =
        DashboardControllerSupport.requireOwnedOrganization(
            request, organizationId, currentPlatformAccount, organizationResolver);
    final OAuthClient client = requireOwnedClient(organizationId, clientId);
    populateDetailModel(model, organizationId, owned.organizationName(), client);
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
        DashboardControllerSupport.requireOwnedOrganization(
            request, organizationId, currentPlatformAccount, organizationResolver);

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

    populateDetailModel(model, organizationId, owned.organizationName(), result.client());
    model.addAttribute(JUST_REGISTERED_RAW_SECRET_ATTRIBUTE, result.rawClientSecret());
    model.addAttribute(JUST_REGISTERED_CLIENT_ID_ATTRIBUTE, result.client().clientId());
    return DETAIL_VIEW;
  }

  // Two exits (HTMX fragment vs. plain redirect) — same rationale as every other dashboard
  // controller's own identical "after a mutation succeeds" suppression. Redirects to the
  // detail page now, not the list — no secret involved in deactivation, so a redirect is safe.
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
      populateDetailModel(
          model,
          organizationId,
          owned.organizationName(),
          requireOwnedClient(organizationId, clientId));
      return DETAIL_FRAGMENT;
    }
    return redirectToDetail(organizationId, clientId);
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
        DashboardControllerSupport.requireOwnedOrganization(
            request, organizationId, currentPlatformAccount, organizationResolver);

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

    populateDetailModel(
        model,
        organizationId,
        owned.organizationName(),
        requireOwnedClient(organizationId, clientId));
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
        DashboardControllerSupport.requireOwnedOrganization(
            request, organizationId, currentPlatformAccount, organizationResolver);

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

    populateDetailModel(
        model,
        organizationId,
        owned.organizationName(),
        requireOwnedClient(organizationId, clientId));
    model.addAttribute(JUST_REGISTERED_RAW_SECRET_ATTRIBUTE, result.rawSecret());
    model.addAttribute(JUST_REGISTERED_CLIENT_ID_ATTRIBUTE, result.clientId());
    return DashboardControllerSupport.isHtmxRequest(request) ? DETAIL_FRAGMENT : DETAIL_VIEW;
  }

  // BR-ORG-06: the one field pair an Organization owner may edit after creation. No secret
  // involved — a plain redirect (non-HTMX) is safe, unlike create()/rotateSecret() above.
  // PMD.CyclomaticComplexity: the new OAuthClientInactiveException catch block, added 2026-09-24
  // as a live UX bug fix, is one more genuinely distinct rejection reason, not incidental
  // branching — same "each branch is a real, separate case the caller needs to see" reasoning
  // OAuthClient#requireWellFormedAbsoluteSecureUri's own identical suppression documents.
  @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.CyclomaticComplexity"})
  @PostMapping("/{clientId}/redirect-settings")
  public String updateRedirectSettings(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final String clientId,
      @ModelAttribute(REDIRECT_SETTINGS_FORM_ATTRIBUTE)
          final UpdateOAuthClientRedirectSettingsForm form,
      final Model model) {
    final DashboardControllerSupport.OwnedOrganization owned =
        DashboardControllerSupport.requireOwnedOrganization(
            request, organizationId, currentPlatformAccount, organizationResolver);

    // Live UX request, 2026-09-24: at least one real redirect URI is required to actually SAVE —
    // without one, /authorize has nothing to match against for this client's own
    // authorization_code grant (BR-CLIENT-01), the primary reason an OAuthClient exists at all.
    // Web-layer-only, not a domain rule: OAuthClient itself still allows zero (the auto-
    // provisioned client's own genuine "not configured yet" state, BR-ORG-06) — this only gates
    // the owner's own deliberate Save action, not every state a client can transiently be in.
    // postLogoutRedirectUris carries no equivalent requirement — SAS's own bare default already
    // covers "not configured" for RP-Initiated Logout (OAuthClient's own constructor comment).
    if (form.parseRedirectUris().isEmpty()) {
      populateDetailModel(
          model,
          organizationId,
          owned.organizationName(),
          requireOwnedClient(organizationId, clientId),
          form);
      model.addAttribute(
          REDIRECT_URIS_ERROR_ATTRIBUTE, "At least one redirect URI is required to save.");
      return DashboardControllerSupport.isHtmxRequest(request) ? DETAIL_FRAGMENT : DETAIL_VIEW;
    }

    try {
      updateRedirectSettings.handle(
          new UpdateOAuthClientRedirectSettingsCommand(
              clientId,
              organizationId,
              form.parseRedirectUris(),
              form.parsePostLogoutRedirectUris(),
              AuditActor.platformAccount(owned.ownerPlatformAccountId())));
    } catch (
        final com.clavaris.clientregistry.application.usecase.updateoauthclientredirectsettings
                .OAuthClientNotFoundException
            _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final OAuthClientInactiveException _) {
      // Live UX bug fix, 2026-09-24: a deactivated client's redirect settings were still
      // editable — see OAuthClientInactiveException's own Javadoc. The form itself is now hidden
      // behind client.active() on the template, so this only fires against a stale page or a
      // direct POST, same defensive posture as every other "not expected via normal navigation,
      // still rejected loudly" guard in this codebase.
      throw new ResponseStatusException(HttpStatus.CONFLICT, INACTIVE_CLIENT_ERROR);
    } catch (final IllegalArgumentException e) {
      // BR-CLIENT-01/OAuthClient's own well-formed/absolute/secure URI validation — surfaced as a
      // 400 here rather than an unhandled 500, same posture every other malformed-input path in
      // this module already takes. Passed as the cause (not just its own message) so the original
      // stack trace survives for anyone debugging a live 400 from this endpoint.
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    }

    if (DashboardControllerSupport.isHtmxRequest(request)) {
      populateDetailModel(
          model,
          organizationId,
          owned.organizationName(),
          requireOwnedClient(organizationId, clientId));
      return DETAIL_FRAGMENT;
    }
    return redirectToDetail(organizationId, clientId);
  }

  // Live UX request, 2026-09-24: reverses BR-ORG-06's original "creation-time-only" rule — see
  // OAuthClientDefaults's own updated Javadoc. Checkbox list, never free text:
  // OAuthGrantTypeCatalog
  // .KNOWN is the only set OAuthClient's own constructor validation (requireKnownGrantTypes)
  // accepts, so an unchecked/unrecognised value 400s here rather than silently reaching
  // OrganizationRegisteredClientRepository, which would otherwise forward it blindly into Spring
  // Authorization Server. PMD.CyclomaticComplexity: four genuinely distinct rejection reasons
  // (not found/inactive/malformed/concurrent conflict) plus the HTMX-vs-redirect branch, same
  // "each branch is a real, separate case" reasoning updateRedirectSettings's own identical
  // suppression documents.
  @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.CyclomaticComplexity"})
  @PostMapping("/{clientId}/grant-types")
  public String updateGrantTypes(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final String clientId,
      @RequestParam(name = "allowedGrantTypes", required = false)
          final List<String> allowedGrantTypes,
      final Model model) {
    final DashboardControllerSupport.OwnedOrganization owned =
        DashboardControllerSupport.requireOwnedOrganization(
            request, organizationId, currentPlatformAccount, organizationResolver);

    try {
      updateGrantTypes.handle(
          new UpdateOAuthClientGrantTypesCommand(
              clientId,
              organizationId,
              allowedGrantTypes == null ? List.of() : allowedGrantTypes,
              AuditActor.platformAccount(owned.ownerPlatformAccountId())));
    } catch (
        final com.clavaris.clientregistry.application.usecase.updateoauthclientgranttypes
                .OAuthClientNotFoundException
            _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (
        final com.clavaris.clientregistry.application.usecase.updateoauthclientgranttypes
                .OAuthClientInactiveException
            _) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, INACTIVE_CLIENT_ERROR);
    } catch (final IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    } catch (final ConcurrentClientModificationException _) {
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    }

    if (DashboardControllerSupport.isHtmxRequest(request)) {
      populateDetailModel(
          model,
          organizationId,
          owned.organizationName(),
          requireOwnedClient(organizationId, clientId));
      return DETAIL_FRAGMENT;
    }
    return redirectToDetail(organizationId, clientId);
  }

  // Same rationale/shape as updateGrantTypes above — free text, add/remove, mirroring Redirect
  // URIs (OidcScopeCatalog.KNOWN is reference only, not an allowlist, so no checkbox restriction
  // here — a real client_credentials-only OAuthClient legitimately needs custom scopes).
  // Blank entries (an in-progress row in the modal's own add/remove UI) are dropped, same
  // tolerance UpdateOAuthClientRedirectSettingsForm's own parse methods already give redirect URIs.
  // PMD.CyclomaticComplexity: same rationale as updateGrantTypes's own identical suppression.
  @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.CyclomaticComplexity"})
  @PostMapping("/{clientId}/scopes")
  public String updateScopes(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final String clientId,
      @RequestParam(name = "allowedScopes", required = false) final List<String> allowedScopes,
      final Model model) {
    final DashboardControllerSupport.OwnedOrganization owned =
        DashboardControllerSupport.requireOwnedOrganization(
            request, organizationId, currentPlatformAccount, organizationResolver);
    final List<String> parsedScopes =
        allowedScopes == null
            ? List.of()
            : allowedScopes.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();

    try {
      updateScopes.handle(
          new UpdateOAuthClientScopesCommand(
              clientId,
              organizationId,
              parsedScopes,
              AuditActor.platformAccount(owned.ownerPlatformAccountId())));
    } catch (
        final com.clavaris.clientregistry.application.usecase.updateoauthclientscopes
                .OAuthClientNotFoundException
            _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (
        final com.clavaris.clientregistry.application.usecase.updateoauthclientscopes
                .OAuthClientInactiveException
            _) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, INACTIVE_CLIENT_ERROR);
    } catch (final IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    } catch (final ConcurrentClientModificationException _) {
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    }

    if (DashboardControllerSupport.isHtmxRequest(request)) {
      populateDetailModel(
          model,
          organizationId,
          owned.organizationName(),
          requireOwnedClient(organizationId, clientId));
      return DETAIL_FRAGMENT;
    }
    return redirectToDetail(organizationId, clientId);
  }

  // Same rationale/shape as updateGrantTypes/updateScopes above — a plain toggle, ADR-0017's own
  // secure-by-default posture stays the starting point, not a floor an owner can never move off.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/{clientId}/consent")
  public String updateConsent(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final String clientId,
      @RequestParam(name = "requireConsent", defaultValue = "false") final boolean requireConsent,
      final Model model) {
    final DashboardControllerSupport.OwnedOrganization owned =
        DashboardControllerSupport.requireOwnedOrganization(
            request, organizationId, currentPlatformAccount, organizationResolver);

    try {
      updateConsent.handle(
          new UpdateOAuthClientConsentCommand(
              clientId,
              organizationId,
              requireConsent,
              AuditActor.platformAccount(owned.ownerPlatformAccountId())));
    } catch (
        final com.clavaris.clientregistry.application.usecase.updateoauthclientconsent
                .OAuthClientNotFoundException
            _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (
        final com.clavaris.clientregistry.application.usecase.updateoauthclientconsent
                .OAuthClientInactiveException
            _) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, INACTIVE_CLIENT_ERROR);
    } catch (final ConcurrentClientModificationException _) {
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    }

    if (DashboardControllerSupport.isHtmxRequest(request)) {
      populateDetailModel(
          model,
          organizationId,
          owned.organizationName(),
          requireOwnedClient(organizationId, clientId));
      return DETAIL_FRAGMENT;
    }
    return redirectToDetail(organizationId, clientId);
  }

  // Live UX request, 2026-09-24: a real add/remove-row list UI, not a one-entry-per-line
  // textarea — matching how Clerk's own multi-entry "Redirect URIs" field actually works. None of
  // the four endpoints below ever calls updateRedirectSettings (the real, persisting use case) —
  // each only mutates this one request's own in-memory form state (one more blank row, or one
  // fewer) and re-renders; nothing is saved until the form's own real "Save redirect settings"
  // submit (updateRedirectSettings above) runs. formaction, not a separate nested <form> (HTML
  // forbids nesting them), is what lets each row's own button target its own URL while still
  // submitting every other row's own already-typed value alongside it — see
  // organization-oauth-client-detail.html's own comment on the row markup itself.
  private String renderAfterRowMutation(
      final HttpServletRequest request,
      final UUID organizationId,
      final String clientId,
      final UpdateOAuthClientRedirectSettingsForm form,
      final Model model) {
    final DashboardControllerSupport.OwnedOrganization owned =
        DashboardControllerSupport.requireOwnedOrganization(
            request, organizationId, currentPlatformAccount, organizationResolver);
    populateDetailModel(
        model,
        organizationId,
        owned.organizationName(),
        requireOwnedClient(organizationId, clientId),
        form);
    return DashboardControllerSupport.isHtmxRequest(request) ? DETAIL_FRAGMENT : DETAIL_VIEW;
  }

  // Always leaves at least one row — an owner who removes the only row still has one blank input
  // to type into, the same "always at least one visible input" convenience
  // UpdateOAuthClientRedirectSettingsForm#from already gives a freshly-loaded (zero-URI) client.
  private static void removeRow(final List<String> rows, final int index) {
    if (index >= 0 && index < rows.size()) {
      rows.remove(index);
    }
    if (rows.isEmpty()) {
      rows.add("");
    }
  }

  @PostMapping("/{clientId}/redirect-settings/redirect-uris/add")
  public String addRedirectUriRow(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final String clientId,
      @ModelAttribute(REDIRECT_SETTINGS_FORM_ATTRIBUTE)
          final UpdateOAuthClientRedirectSettingsForm form,
      final Model model) {
    form.getRedirectUris().add("");
    return renderAfterRowMutation(request, organizationId, clientId, form, model);
  }

  @PostMapping("/{clientId}/redirect-settings/redirect-uris/remove/{index}")
  public String removeRedirectUriRow(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final String clientId,
      @PathVariable final int index,
      @ModelAttribute(REDIRECT_SETTINGS_FORM_ATTRIBUTE)
          final UpdateOAuthClientRedirectSettingsForm form,
      final Model model) {
    removeRow(form.getRedirectUris(), index);
    return renderAfterRowMutation(request, organizationId, clientId, form, model);
  }

  @PostMapping("/{clientId}/redirect-settings/post-logout-redirect-uris/add")
  public String addPostLogoutRedirectUriRow(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final String clientId,
      @ModelAttribute(REDIRECT_SETTINGS_FORM_ATTRIBUTE)
          final UpdateOAuthClientRedirectSettingsForm form,
      final Model model) {
    form.getPostLogoutRedirectUris().add("");
    return renderAfterRowMutation(request, organizationId, clientId, form, model);
  }

  @PostMapping("/{clientId}/redirect-settings/post-logout-redirect-uris/remove/{index}")
  public String removePostLogoutRedirectUriRow(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final String clientId,
      @PathVariable final int index,
      @ModelAttribute(REDIRECT_SETTINGS_FORM_ATTRIBUTE)
          final UpdateOAuthClientRedirectSettingsForm form,
      final Model model) {
    removeRow(form.getPostLogoutRedirectUris(), index);
    return renderAfterRowMutation(request, organizationId, clientId, form, model);
  }
}
