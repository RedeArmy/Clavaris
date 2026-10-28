package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.getoauthclientfororganization.GetOAuthClientForOrganizationQuery;
import com.clavaris.clientregistry.application.usecase.getoauthclientfororganization.GetOAuthClientForOrganizationUseCase;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;

/**
 * What the OAuth client controllers share (TD-ARCH-042): finding a client that really belongs to
 * the Organization in the URL, and building the model the detail page renders. Split out when
 * {@code PlatformOAuthClientController} was divided by concern, so each of the three keeps its own
 * collaborators and none carries the others'.
 */
// PMD.LongVariable: REDIRECT_SETTINGS_FORM_ATTRIBUTE names exactly what it is, the model key the
// detail template reads.
@SuppressWarnings("PMD.LongVariable")
@Component
class PlatformOAuthClientSupport {

  private static final String DETAIL_FRAGMENT =
      "clientregistry/platform/organization-oauth-client-detail :: detail";

  private static final String REDIRECT_SETTINGS_FORM_ATTRIBUTE = "redirectSettingsForm";
  private static final String ORGANIZATION_ID_ATTRIBUTE = "organizationId";
  private static final String ORGANIZATION_NAME_ATTRIBUTE = "organizationName";

  private final GetOAuthClientForOrganizationUseCase getClient;
  private final String clavarisBaseUrl;

  /* package */ PlatformOAuthClientSupport(
      final GetOAuthClientForOrganizationUseCase getClient,
      @Value("${CLAVARIS_BASE_URL:http://localhost:8080}") final String clavarisBaseUrl) {
    this.getClient = getClient;
    this.clavarisBaseUrl = clavarisBaseUrl;
  }

  // Shared by every one of this controller's own detail-page renders (GET, create, rotate-secret,
  // deactivate's own HTMX branch) — organizationId/organizationName/client/its own redirect-
  // settings form (pre-filled from the client's current lists)/its setup-instructions panel always
  // travel together.
  /* package */ void populateDetailModel(
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
  /* package */ void populateDetailModel(
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

  /* package */ OAuthClient requireOwnedClient(final UUID organizationId, final String clientId) {
    return getClient
        .handle(new GetOAuthClientForOrganizationQuery(clientId, organizationId))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  // Shared by every plain (non-HTMX) success exit on this controller — six call sites as of
  // 2026-09-24 (deactivate/activate/redirect-settings/grant-types/scopes/consent), extracted once
  // real duplication set in, not preemptively.
  /* package */ static String redirectToDetail(final UUID organizationId, final String clientId) {
    return "redirect:/platform/dashboard/organizations/"
        + organizationId
        + "/oauth-clients/"
        + clientId;
  }

  // What every configuration edit answers once it has succeeded: an HTMX request gets the detail
  // fragment to swap in place, a plain form post is redirected to the detail page. Shared by the
  // edits that end this way so the branch is written once.
  /* package */ String respondAfterUpdate(
      final HttpServletRequest request,
      final Model model,
      final UUID organizationId,
      final String organizationName,
      final String clientId) {
    if (DashboardControllerSupport.isHtmxRequest(request)) {
      populateDetailModel(
          model, organizationId, organizationName, requireOwnedClient(organizationId, clientId));
      return DETAIL_FRAGMENT;
    }
    return redirectToDetail(organizationId, clientId);
  }
}
