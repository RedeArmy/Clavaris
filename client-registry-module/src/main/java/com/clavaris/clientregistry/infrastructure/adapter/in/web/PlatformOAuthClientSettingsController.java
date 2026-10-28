package com.clavaris.clientregistry.infrastructure.adapter.in.web;

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
import com.clavaris.common.domain.model.AuditActor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * The OAuth client detail page's configuration edits (redirect settings, grant types, scopes,
 * consent), split from {@link PlatformOAuthClientController} (TD-ARCH-042), which keeps the list,
 * the detail page and the lifecycle actions. Same base path, same ownership rule: every handler
 * re-checks that the Organization belongs to the signed-in operator and that the client belongs to
 * the Organization, through {@link PlatformOAuthClientSupport}.
 */
@SuppressWarnings({"PMD.LongVariable"})
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/oauth-clients")
public class PlatformOAuthClientSettingsController {

  private static final String DETAIL_VIEW =
      "clientregistry/platform/organization-oauth-client-detail";
  private static final String DETAIL_FRAGMENT = DETAIL_VIEW + " :: detail";
  private static final String REDIRECT_SETTINGS_FORM_ATTRIBUTE = "redirectSettingsForm";
  private static final String REDIRECT_URIS_ERROR_ATTRIBUTE = "redirectUrisError";
  private static final String INACTIVE_CLIENT_ERROR = "This OAuthClient is inactive.";

  private final UpdateOAuthClientRedirectSettingsUseCase updateRedirectSettings;
  private final UpdateOAuthClientGrantTypesUseCase updateGrantTypes;
  private final UpdateOAuthClientScopesUseCase updateScopes;
  private final UpdateOAuthClientConsentUseCase updateConsent;
  private final OrganizationForPlatformAccountResolver organizationResolver;
  private final CurrentPlatformAccountResolver currentPlatformAccount;
  private final PlatformOAuthClientSupport support;

  public PlatformOAuthClientSettingsController(
      final UpdateOAuthClientRedirectSettingsUseCase updateRedirectSettings,
      final UpdateOAuthClientGrantTypesUseCase updateGrantTypes,
      final UpdateOAuthClientScopesUseCase updateScopes,
      final UpdateOAuthClientConsentUseCase updateConsent,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount,
      final PlatformOAuthClientSupport support) {
    this.updateRedirectSettings = updateRedirectSettings;
    this.updateGrantTypes = updateGrantTypes;
    this.updateScopes = updateScopes;
    this.updateConsent = updateConsent;
    this.organizationResolver = organizationResolver;
    this.currentPlatformAccount = currentPlatformAccount;
    this.support = support;
  }

  // BR-ORG-06: the one field pair an Organization owner may edit after creation. No secret
  // involved — a plain redirect (non-HTMX) is safe, unlike create()/rotateSecret() above.
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
      support.populateDetailModel(
          model,
          organizationId,
          owned.organizationName(),
          support.requireOwnedClient(organizationId, clientId),
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

    return support.respondAfterUpdate(
        request, model, organizationId, owned.organizationName(), clientId);
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
  @SuppressWarnings({"PMD.CyclomaticComplexity"})
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

    return support.respondAfterUpdate(
        request, model, organizationId, owned.organizationName(), clientId);
  }

  // Same rationale/shape as updateGrantTypes above — free text, add/remove, mirroring Redirect
  // URIs (OidcScopeCatalog.KNOWN is reference only, not an allowlist, so no checkbox restriction
  // here — a real client_credentials-only OAuthClient legitimately needs custom scopes).
  // Blank entries (an in-progress row in the modal's own add/remove UI) are dropped, same
  // tolerance UpdateOAuthClientRedirectSettingsForm's own parse methods already give redirect URIs.
  // PMD.CyclomaticComplexity: same rationale as updateGrantTypes's own identical suppression.
  @SuppressWarnings({"PMD.CyclomaticComplexity"})
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

    return support.respondAfterUpdate(
        request, model, organizationId, owned.organizationName(), clientId);
  }

  // Same rationale/shape as updateGrantTypes/updateScopes above — a plain toggle, ADR-0017's own
  // secure-by-default posture stays the starting point, not a floor an owner can never move off.
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

    return support.respondAfterUpdate(
        request, model, organizationId, owned.organizationName(), clientId);
  }
}
