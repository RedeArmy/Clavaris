package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The add and remove buttons of the redirect-URI lists on the OAuth client detail page: they
 * re-render the detail with the in-progress rows and persist nothing. Split from {@link
 * PlatformOAuthClientController} (TD-ARCH-042).
 */
@SuppressWarnings({"PMD.LongVariable"})
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/oauth-clients")
public class PlatformOAuthClientRedirectUriRowsController {

  private static final String DETAIL_VIEW =
      "clientregistry/platform/organization-oauth-client-detail";
  private static final String DETAIL_FRAGMENT = DETAIL_VIEW + " :: detail";
  private static final String REDIRECT_SETTINGS_FORM_ATTRIBUTE = "redirectSettingsForm";

  private final OrganizationForPlatformAccountResolver organizationResolver;
  private final CurrentPlatformAccountResolver currentPlatformAccount;
  private final PlatformOAuthClientSupport support;

  public PlatformOAuthClientRedirectUriRowsController(
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount,
      final PlatformOAuthClientSupport support) {
    this.organizationResolver = organizationResolver;
    this.currentPlatformAccount = currentPlatformAccount;
    this.support = support;
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
    support.populateDetailModel(
        model,
        organizationId,
        owned.organizationName(),
        support.requireOwnedClient(organizationId, clientId),
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
