package com.clavaris.webhook.infrastructure.adapter.in.web;

import com.clavaris.webhook.application.usecase.listwebhookendpointsfororganization.ListWebhookEndpointsForOrganizationQuery;
import com.clavaris.webhook.application.usecase.listwebhookendpointsfororganization.ListWebhookEndpointsForOrganizationUseCase;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Package-private helpers shared by every webhook-module dashboard controller — {@link
 * PlatformWebhookEndpointController} and {@link PlatformWebhookDeliveryController} both need the
 * exact same ownership-resolution/HTMX-detection logic, and duplicating it a second time is the
 * real `pmd:cpd-check` duplication CI already caught once between the two client-registry- module
 * dashboard controllers — see {@code DashboardControllerSupport}'s own identical rationale there.
 * Extracted proactively here rather than waiting for CI to flag it again. PMD.LongVariable: every
 * parameter here names exactly what it is — same deliberate, descriptive-name convention {@code
 * DashboardControllerSupport}'s own identical suppression already establishes.
 */
@SuppressWarnings("PMD.LongVariable")
final class WebhookDashboardControllerSupport {

  // HTMX's own request header (https://htmx.org/reference/#request_headers) — same convention as
  // every other dashboard controller's own identical constant.
  private static final String HX_REQUEST_HEADER = "HX-Request";

  private WebhookDashboardControllerSupport() {
    // Utility class — never instantiated.
  }

  /* package */ static boolean isHtmxRequest(final HttpServletRequest request) {
    return "true".equals(request.getHeader(HX_REQUEST_HEADER));
  }

  // Same rationale as every other dashboard controller's own identical method.
  /* package */ static UUID requireCurrentPlatformAccount(
      final CurrentPlatformAccountResolver currentPlatformAccount,
      final HttpServletRequest request) {
    return currentPlatformAccount
        .resolve(request)
        .orElseThrow(
            () -> new IllegalStateException("No authenticated PlatformAccount on this request"));
  }

  /* package */ static String requireOwnedOrganizationName(
      final OrganizationForPlatformAccountResolver organizationResolver,
      final UUID organizationId,
      final UUID ownerPlatformAccountId) {
    return organizationResolver
        .resolveName(organizationId, ownerPlatformAccountId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  // The anti-enumeration check Deactivate/Activate/RotateWebhookEndpointSecretCommand — and now
  // ListWebhookDeliveriesForEndpointQuery/ReplayWebhookDeliveryCommand — can't do themselves: none
  // of them carries an organizationId, all key off endpointId alone. Reuses the already-
  // organizationId-scoped ListWebhookEndpointsForOrganizationUseCase rather than adding a new
  // "get one endpoint" port, so an endpointId belonging to a different Organization 404s before
  // any mutating/reading use case ever runs. Returns the resolved WebhookEndpoint itself (not just
  // a boolean) since PlatformWebhookDeliveryController's own breadcrumb needs its URL.
  /* package */ static WebhookEndpoint requireEndpointBelongsToOrganization(
      final ListWebhookEndpointsForOrganizationUseCase listEndpoints,
      final UUID organizationId,
      final UUID endpointId) {
    return listEndpoints
        .handle(new ListWebhookEndpointsForOrganizationQuery(organizationId))
        .stream()
        .filter(endpoint -> endpoint.id().equals(endpointId))
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }
}
