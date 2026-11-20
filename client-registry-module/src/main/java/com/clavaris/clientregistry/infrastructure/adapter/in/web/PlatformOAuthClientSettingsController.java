package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.setclientbranding.SetClientBrandingCommand;
import com.clavaris.clientregistry.application.usecase.setclientbranding.SetClientBrandingUseCase;
import com.clavaris.clientregistry.application.usecase.updateoauthclientaccesstokenttl.UpdateOAuthClientAccessTokenTtlCommand;
import com.clavaris.clientregistry.application.usecase.updateoauthclientaccesstokenttl.UpdateOAuthClientAccessTokenTtlUseCase;
import com.clavaris.clientregistry.application.usecase.updateoauthclientconsent.UpdateOAuthClientConsentCommand;
import com.clavaris.clientregistry.application.usecase.updateoauthclientconsent.UpdateOAuthClientConsentUseCase;
import com.clavaris.clientregistry.application.usecase.updateoauthclientgranttypes.UpdateOAuthClientGrantTypesCommand;
import com.clavaris.clientregistry.application.usecase.updateoauthclientgranttypes.UpdateOAuthClientGrantTypesUseCase;
import com.clavaris.clientregistry.application.usecase.updateoauthclientpkcepolicy.UpdateOAuthClientPkcePolicyCommand;
import com.clavaris.clientregistry.application.usecase.updateoauthclientpkcepolicy.UpdateOAuthClientPkcePolicyUseCase;
import com.clavaris.clientregistry.application.usecase.updateoauthclientredirectsettings.OAuthClientInactiveException;
import com.clavaris.clientregistry.application.usecase.updateoauthclientredirectsettings.UpdateOAuthClientRedirectSettingsCommand;
import com.clavaris.clientregistry.application.usecase.updateoauthclientredirectsettings.UpdateOAuthClientRedirectSettingsUseCase;
import com.clavaris.clientregistry.application.usecase.updateoauthclientscopes.UpdateOAuthClientScopesCommand;
import com.clavaris.clientregistry.application.usecase.updateoauthclientscopes.UpdateOAuthClientScopesUseCase;
import com.clavaris.clientregistry.domain.model.ConcurrentClientModificationException;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.clientregistry.domain.model.PkcePolicy;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.domain.model.InvalidInputException;
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
  private final UpdateOAuthClientPkcePolicyUseCase updatePkcePolicy;
  private final UpdateOAuthClientAccessTokenTtlUseCase updateAccessTokenTtl;
  private final SetClientBrandingUseCase setBranding;
  private final PlatformOAuthClientSupport support;

  public PlatformOAuthClientSettingsController(
      final UpdateOAuthClientRedirectSettingsUseCase updateRedirectSettings,
      final UpdateOAuthClientGrantTypesUseCase updateGrantTypes,
      final UpdateOAuthClientScopesUseCase updateScopes,
      final UpdateOAuthClientConsentUseCase updateConsent,
      final UpdateOAuthClientPkcePolicyUseCase updatePkcePolicy,
      final UpdateOAuthClientAccessTokenTtlUseCase updateAccessTokenTtl,
      final SetClientBrandingUseCase setBranding,
      final PlatformOAuthClientSupport support) {
    this.updateRedirectSettings = updateRedirectSettings;
    this.updateGrantTypes = updateGrantTypes;
    this.updateScopes = updateScopes;
    this.updateConsent = updateConsent;
    this.updatePkcePolicy = updatePkcePolicy;
    this.updateAccessTokenTtl = updateAccessTokenTtl;
    this.setBranding = setBranding;
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
        support.requireOwnedOrganization(request, organizationId);

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
        support.requireOwnedOrganization(request, organizationId);

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
        support.requireOwnedOrganization(request, organizationId);
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
        support.requireOwnedOrganization(request, organizationId);

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

  // ADR-0043: whether the client must send a PKCE challenge. The value is posted as a name, never
  // as
  // a flag: an absent or unreadable field falls back to REQUIRED, so a blank or crafted POST can
  // only
  // ever land on the strict side. Moving to OPTIONAL is a button of its own on the page, behind a
  // confirmation, and the use case audits both directions with what the policy was and became.
  @PostMapping("/{clientId}/pkce-policy")
  public String updatePkcePolicy(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final String clientId,
      @RequestParam(name = "pkcePolicy", defaultValue = "REQUIRED") final PkcePolicy pkcePolicy,
      final Model model) {
    final DashboardControllerSupport.OwnedOrganization owned =
        support.requireOwnedOrganization(request, organizationId);

    try {
      updatePkcePolicy.handle(
          new UpdateOAuthClientPkcePolicyCommand(
              clientId,
              organizationId,
              pkcePolicy,
              AuditActor.platformAccount(owned.ownerPlatformAccountId())));
    } catch (
        final com.clavaris.clientregistry.application.usecase.updateoauthclientpkcepolicy
                .OAuthClientNotFoundException
            _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (
        final com.clavaris.clientregistry.application.usecase.updateoauthclientpkcepolicy
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

  // TD-SEC-104 / ADR-0049: how long an access token issued to this client lives. A blank field
  // puts the client back on the default; anything else is a whole number of seconds, and what the
  // domain refuses (outside 60 to 3600) is a 400 here, with the reason, because the operator typed
  // it by hand. Audited with what it was and what it became.
  @PostMapping("/{clientId}/access-token-ttl")
  public String updateAccessTokenTtl(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final String clientId,
      @RequestParam(name = "accessTokenTtlSeconds", required = false)
          final String accessTokenTtlSeconds,
      final Model model) {
    final DashboardControllerSupport.OwnedOrganization owned =
        support.requireOwnedOrganization(request, organizationId);
    final Integer seconds = parseSeconds(accessTokenTtlSeconds);

    try {
      updateAccessTokenTtl.handle(
          new UpdateOAuthClientAccessTokenTtlCommand(
              clientId,
              organizationId,
              seconds,
              AuditActor.platformAccount(owned.ownerPlatformAccountId())));
    } catch (
        final com.clavaris.clientregistry.application.usecase.updateoauthclientaccesstokenttl
                .OAuthClientNotFoundException
            _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (
        final com.clavaris.clientregistry.application.usecase.updateoauthclientaccesstokenttl
                .OAuthClientInactiveException
            _) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, INACTIVE_CLIENT_ERROR);
    } catch (final InvalidInputException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    } catch (final ConcurrentClientModificationException _) {
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    }

    return support.respondAfterUpdate(
        request, model, organizationId, owned.organizationName(), clientId);
  }

  private static Integer parseSeconds(final String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Integer.valueOf(raw.strip());
    } catch (final NumberFormatException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "accessTokenTtlSeconds must be a whole number of seconds", e);
    }
  }

  // ADR-0009 §3: how this client's hosted sign-in page looks. A blank field means "not set", so the
  // page falls back to the default; what the domain refuses (a logo that is not an https URL, a
  // colour that is not #rgb or #rrggbb, a name over its limit) is shown on the page, not as a 400,
  // because here the operator typed it by hand. The colour is interpolated into the page's style,
  // which is why the domain accepts only a hex colour and nothing else.
  @PostMapping("/{clientId}/branding")
  public String updateBranding(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final String clientId,
      @RequestParam(name = "logoUrl", required = false) final String logoUrl,
      @RequestParam(name = "primaryColor", required = false) final String primaryColor,
      @RequestParam(name = "applicationDisplayName", required = false)
          final String applicationDisplayName,
      final Model model) {
    final DashboardControllerSupport.OwnedOrganization owned =
        support.requireOwnedOrganization(request, organizationId);
    final OAuthClient client = support.requireOwnedClient(organizationId, clientId);

    try {
      setBranding.handle(
          new SetClientBrandingCommand(
              organizationId,
              client.id(),
              blankToNull(logoUrl),
              blankToNull(primaryColor),
              blankToNull(applicationDisplayName),
              AuditActor.platformAccount(owned.ownerPlatformAccountId())));
    } catch (final IllegalArgumentException _) {
      return support.respondWithError(
          request, model, organizationId, owned.organizationName(), clientId, "brandingError");
    } catch (
        final com.clavaris.clientregistry.application.usecase.setclientbranding
                .OAuthClientNotFoundException
            _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    return support.respondAfterUpdate(
        request, model, organizationId, owned.organizationName(), clientId);
  }

  private static String blankToNull(final String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
