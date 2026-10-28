package com.clavaris.webhook.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.webhook.application.usecase.getwebhookendpointfororganization.GetWebhookEndpointForOrganizationUseCase;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.UnsafeWebhookUrlException;
import com.clavaris.webhook.application.usecase.updatewebhookendpointdescription.UpdateWebhookEndpointDescriptionCommand;
import com.clavaris.webhook.application.usecase.updatewebhookendpointdescription.UpdateWebhookEndpointDescriptionUseCase;
import com.clavaris.webhook.application.usecase.updatewebhookendpointeventtypes.UpdateWebhookEndpointEventTypesCommand;
import com.clavaris.webhook.application.usecase.updatewebhookendpointeventtypes.UpdateWebhookEndpointEventTypesUseCase;
import com.clavaris.webhook.application.usecase.updatewebhookendpointurl.UpdateWebhookEndpointUrlCommand;
import com.clavaris.webhook.application.usecase.updatewebhookendpointurl.UpdateWebhookEndpointUrlUseCase;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The three single-field edits of a webhook endpoint (URL, description, subscribed event types),
 * split out of {@link PlatformWebhookEndpointController} (TD-ARCH-042) the way the OAuth client
 * settings were: the list/register/detail/lifecycle controller keeps its collaborators and this one
 * carries only the update use cases. Same ownership rule as there — the endpoint is resolved
 * through {@code GetWebhookEndpointForOrganizationUseCase} first, so an {@code endpointId} of
 * another Organization 404s before any update runs. A rejected URL or an empty event-type selection
 * re-renders the detail page with the form error instead of redirecting, so the value the owner
 * just typed is not lost to a fresh GET.
 */
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/webhook-endpoints")
public class PlatformWebhookEndpointSettingsController {

  private static final String ENDPOINT_ATTRIBUTE = "endpoint";
  private static final String DETAIL_VIEW = "webhook/platform/organization-webhook-endpoint-detail";

  private final GetWebhookEndpointForOrganizationUseCase getEndpoint;
  private final UpdateWebhookEndpointUrlUseCase updateEndpointUrl;
  private final UpdateWebhookEndpointDescriptionUseCase updateEndpointDescription;
  private final UpdateWebhookEndpointEventTypesUseCase updateEndpointEventTypes;
  private final OrganizationForPlatformAccountResolver organizationResolver;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  // java:S107: one parameter per collaborating port.
  @SuppressWarnings("java:S107")
  public PlatformWebhookEndpointSettingsController(
      final GetWebhookEndpointForOrganizationUseCase getEndpoint,
      final UpdateWebhookEndpointUrlUseCase updateEndpointUrl,
      final UpdateWebhookEndpointDescriptionUseCase updateEndpointDescription,
      final UpdateWebhookEndpointEventTypesUseCase updateEndpointEventTypes,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getEndpoint = getEndpoint;
    this.updateEndpointUrl = updateEndpointUrl;
    this.updateEndpointDescription = updateEndpointDescription;
    this.updateEndpointEventTypes = updateEndpointEventTypes;
    this.organizationResolver = organizationResolver;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  // Never returns "redirect:" on the rejected-URL path — same rationale as create() above: the
  // value the owner just typed would be lost to a fresh GET.
  @PostMapping("/{endpointId}/url")
  public String updateUrl(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID endpointId,
      @RequestParam final String url,
      final Model model) {
    final UUID ownerPlatformAccountId =
        WebhookDashboardControllerSupport.requireCurrentPlatformAccount(
            currentPlatformAccount, request);
    final String organizationName =
        WebhookDashboardControllerSupport.requireOwnedOrganizationName(
            organizationResolver, organizationId, ownerPlatformAccountId);
    WebhookDashboardControllerSupport.requireEndpointBelongsToOrganization(
        getEndpoint, organizationId, endpointId);

    final WebhookEndpoint updated;
    try {
      updated =
          updateEndpointUrl.handle(
              new UpdateWebhookEndpointUrlCommand(
                  endpointId, url, AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final UnsafeWebhookUrlException _) {
      // Same "form error, not a bare status code" reasoning as create()'s own identical catch.
      WebhookDashboardControllerSupport.populateHeaderModel(
          model, organizationId, organizationName);
      model.addAttribute(
          ENDPOINT_ATTRIBUTE,
          WebhookDashboardControllerSupport.requireEndpointBelongsToOrganization(
              getEndpoint, organizationId, endpointId));
      model.addAttribute("unsafeWebhookUrlError", true);
      return DETAIL_VIEW;
    }

    WebhookDashboardControllerSupport.populateHeaderModel(model, organizationId, organizationName);
    model.addAttribute(ENDPOINT_ATTRIBUTE, updated);
    return WebhookDashboardControllerSupport.redirectToDetail(organizationId, endpointId);
  }

  @PostMapping("/{endpointId}/description")
  public String updateDescription(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID endpointId,
      @RequestParam(required = false) final String description) {
    final UUID ownerPlatformAccountId =
        WebhookDashboardControllerSupport.requireCurrentPlatformAccount(
            currentPlatformAccount, request);
    WebhookDashboardControllerSupport.requireOwnedOrganizationName(
        organizationResolver, organizationId, ownerPlatformAccountId);
    WebhookDashboardControllerSupport.requireEndpointBelongsToOrganization(
        getEndpoint, organizationId, endpointId);

    updateEndpointDescription.handle(
        new UpdateWebhookEndpointDescriptionCommand(
            endpointId, description, AuditActor.platformAccount(ownerPlatformAccountId)));

    return WebhookDashboardControllerSupport.redirectToDetail(organizationId, endpointId);
  }

  @PostMapping("/{endpointId}/event-types")
  public String updateEventTypes(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID endpointId,
      @RequestParam(required = false) final List<String> subscribedEventTypes,
      final Model model) {
    final UUID ownerPlatformAccountId =
        WebhookDashboardControllerSupport.requireCurrentPlatformAccount(
            currentPlatformAccount, request);
    final String organizationName =
        WebhookDashboardControllerSupport.requireOwnedOrganizationName(
            organizationResolver, organizationId, ownerPlatformAccountId);
    final WebhookEndpoint existing =
        WebhookDashboardControllerSupport.requireEndpointBelongsToOrganization(
            getEndpoint, organizationId, endpointId);

    if (subscribedEventTypes == null || subscribedEventTypes.isEmpty()) {
      // Same "form error, not a bare status code" reasoning as create()'s own identical catch —
      // WebhookEndpoint#updateEventTypes would otherwise throw IllegalArgumentException
      // (BR-WEBHOOK-06) for a caller this dashboard form can genuinely produce (every checkbox
      // and the "All events" toggle both left unchecked).
      WebhookDashboardControllerSupport.populateHeaderModel(
          model, organizationId, organizationName);
      model.addAttribute(ENDPOINT_ATTRIBUTE, existing);
      model.addAttribute("emptyEventTypesError", true);
      return DETAIL_VIEW;
    }

    updateEndpointEventTypes.handle(
        new UpdateWebhookEndpointEventTypesCommand(
            endpointId, subscribedEventTypes, AuditActor.platformAccount(ownerPlatformAccountId)));

    return WebhookDashboardControllerSupport.redirectToDetail(organizationId, endpointId);
  }
}
