package com.clavaris.webhook.infrastructure.adapter.in.web;

import com.clavaris.webhook.application.usecase.getwebhookendpointfororganization.GetWebhookEndpointForOrganizationQuery;
import com.clavaris.webhook.application.usecase.getwebhookendpointfororganization.GetWebhookEndpointForOrganizationUseCase;
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

  // The anti-enumeration check Deactivate/Activate/RotateWebhookEndpointSecretCommand — and
  // ListWebhookDeliveriesForEndpointQuery/ReplayWebhookDeliveryCommand — can't do themselves: none
  // of them carries an organizationId, all key off endpointId alone. TD-PERF-026 (SDE-III review,
  // 2026-09-16): now backed by the O(1) GetWebhookEndpointForOrganizationUseCase, not the former
  // "fetch every endpoint for the Organization, scan in memory for a match" workaround this method
  // used to inline (see WebhookEndpointRepository#findByIdAndOrganizationId's own Javadoc for the
  // full before/after) — an endpointId belonging to a different Organization still 404s before any
  // mutating/reading use case ever runs, just without the O(n) cost on every admin click. Returns
  // the resolved WebhookEndpoint itself (not just a boolean) since
  // PlatformWebhookDeliveryController's
  // own breadcrumb needs its URL.
  /* package */ static WebhookEndpoint requireEndpointBelongsToOrganization(
      final GetWebhookEndpointForOrganizationUseCase getEndpoint,
      final UUID organizationId,
      final UUID endpointId) {
    return getEndpoint
        .handle(new GetWebhookEndpointForOrganizationQuery(organizationId, endpointId))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }
}
