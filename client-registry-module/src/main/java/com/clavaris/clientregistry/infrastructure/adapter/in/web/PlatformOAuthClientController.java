package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.activateoauthclient.ActivateOAuthClientCommand;
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
import com.clavaris.clientregistry.application.usecase.updateoauthclientredirectsettings.OAuthClientInactiveException;
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
// suppression). PMD.TooManyMethods/GodClass: the four add/remove-row endpoints (live UX request,
// 2026-09-24 — a real list UI, not a textarea) plus the new activate() (2026-09-24, symmetric with
// the already-existing deactivate()) are each a genuinely distinct HTTP mapping, not sprawl — same
// "wiring, not sprawl" reasoning OrganizationUseCaseConfig's own class-level suppression documents
// for an identical situation; the alternative (splitting this into several controllers per
// sub-resource) would scatter the ownership-check/populateDetailModel plumbing every one of these
// endpoints shares, not remove any real complexity. PMD.ExcessiveParameterList: one constructor
// parameter per collaborating use case, same rationale as every other multi-collaborator
// constructor in this codebase (see e.g. RegisterOAuthClientService's own java:S107 precedent) —
// a synthetic parameter object here would add indirection without removing complexity.
@SuppressWarnings({
  "PMD.LongVariable",
  "PMD.ExcessiveImports",
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.TooManyMethods",
  "PMD.GodClass",
  "PMD.ExcessiveParameterList"
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

  private final RegisterOAuthClientUseCase registerClient;
  private final ListOAuthClientsPagedUseCase listClientsPaged;
  private final GetOAuthClientForOrganizationUseCase getClient;
  private final DeactivateOAuthClientUseCase deactivateClient;
  private final ActivateOAuthClientUseCase activateClient;
  private final RotateOAuthClientSecretUseCase rotateClientSecret;
  private final UpdateOAuthClientRedirectSettingsUseCase updateRedirectSettings;
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
    model.addAttribute("justRegisteredRawSecret", result.rawClientSecret());
    model.addAttribute("justRegisteredClientId", result.client().clientId());
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
    return "redirect:/platform/dashboard/organizations/"
        + organizationId
        + "/oauth-clients/"
        + clientId;
  }

  // Live UX request, 2026-09-24: reactivates a previously deactivated client — same two-exit
  // shape as deactivate() above.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/{clientId}/activate")
  public String activate(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final String clientId,
      final Model model) {
    final DashboardControllerSupport.OwnedOrganization owned =
        DashboardControllerSupport.requireOwnedOrganization(
            request, organizationId, currentPlatformAccount, organizationResolver);

    try {
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

    if (DashboardControllerSupport.isHtmxRequest(request)) {
      populateDetailModel(
          model,
          organizationId,
          owned.organizationName(),
          requireOwnedClient(organizationId, clientId));
      return DETAIL_FRAGMENT;
    }
    return "redirect:/platform/dashboard/organizations/"
        + organizationId
        + "/oauth-clients/"
        + clientId;
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
    model.addAttribute("justRegisteredRawSecret", result.rawSecret());
    model.addAttribute("justRegisteredClientId", result.clientId());
    return DashboardControllerSupport.isHtmxRequest(request) ? DETAIL_FRAGMENT : DETAIL_VIEW;
  }

  // BR-ORG-06: the one field pair an Organization owner may edit after creation. No secret
  // involved — a plain redirect (non-HTMX) is safe, unlike create()/rotateSecret() above.
  // PMD.CyclomaticComplexity: the new OAuthClientInactiveException catch (2026-09-24, live UX bug
  // fix) is one more genuinely distinct rejection reason, not incidental branching — same "each
  // branch is a real, separate case the caller needs to see" reasoning
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
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This OAuthClient is inactive.");
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
    return "redirect:/platform/dashboard/organizations/"
        + organizationId
        + "/oauth-clients/"
        + clientId;
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
