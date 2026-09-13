package com.clavaris.webhook.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.webhook.application.usecase.listwebhookdeliveriesforendpoint.ListWebhookDeliveriesForEndpointQuery;
import com.clavaris.webhook.application.usecase.listwebhookdeliveriesforendpoint.ListWebhookDeliveriesForEndpointUseCase;
import com.clavaris.webhook.application.usecase.listwebhookendpointsfororganization.ListWebhookEndpointsForOrganizationUseCase;
import com.clavaris.webhook.application.usecase.replaywebhookdelivery.ReplayWebhookDeliveryCommand;
import com.clavaris.webhook.application.usecase.replaywebhookdelivery.ReplayWebhookDeliveryUseCase;
import com.clavaris.webhook.application.usecase.replaywebhookdelivery.WebhookDeliveryNotFoundException;
import com.clavaris.webhook.application.usecase.replaywebhookdelivery.WebhookDeliveryNotReplayableException;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * ADR-0025, ADR-0007, BR-WEBHOOK-03: the dashboard's own webhook delivery history/replay view — the
 * one item {@code technical-debt-register.md}'s own TD-FUT-032 row explicitly named as still
 * missing once {@link PlatformWebhookEndpointController} shipped endpoint/subscription management
 * without it. Reuses the exact same use cases the REST admin API already exposes ({@link
 * ListWebhookDeliveriesForEndpointUseCase}, {@link ReplayWebhookDeliveryUseCase}) with a second,
 * session-authenticated {@link AuditActor#platformAccount} caller — same "reuse, don't reimplement"
 * posture every prior dashboard increment in this codebase already establishes.
 *
 * <p>{@code endpointId} resolves through {@link WebhookDashboardControllerSupport}'s shared
 * ownership helpers — the same anti-enumeration posture {@link PlatformWebhookEndpointController}
 * already established for {@code :deactivate}/{@code :activate}/{@code :rotate-secret}, now shared
 * rather than duplicated a second time (the exact `pmd:cpd-check` lesson that class's own history
 * already taught). Neither {@link ListWebhookDeliveriesForEndpointQuery} nor {@link
 * ReplayWebhookDeliveryCommand} carries an {@code organizationId} of its own — both key off {@code
 * endpointId} alone — so this controller confirms the endpoint actually belongs to the resolved
 * Organization before either use case ever runs, not after.
 *
 * <p>{@link ReplayWebhookDeliveryCommand}'s own endpoint-ownership guard (the {@code deliveryId}
 * must belong to this {@code endpointId}, not just exist) is authoritative and re-checked inside
 * {@code ReplayWebhookDeliveryService} itself — this controller does not duplicate that specific
 * check, only the coarser "does this endpoint belong to this Organization" one neither use case can
 * do on its own. A {@link WebhookDeliveryNotFoundException} (unknown id, or a real cross-endpoint
 * mismatch) 404s; a {@link WebhookDeliveryNotReplayableException} (still owned by the ordinary
 * retry engine) surfaces as an inline page error, not a raw 409 — same "a human filling out a form
 * needs to see why," not just a bare status code, posture {@code UnsafeWebhookUrlException}'s own
 * handling in the sibling controller already establishes. Replay carries no secret, so it keeps
 * this codebase's normal "success redirects, HTMX gets a fragment" convention — unlike
 * register/rotate-secret on the endpoints page.
 */
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping(
    "/platform/dashboard/organizations/{organizationId}/webhook-endpoints/{endpointId}/deliveries")
public class PlatformWebhookDeliveryController {

  private static final String LIST_VIEW = "webhook/platform/webhook-endpoint-deliveries";
  private static final String DELIVERIES_FRAGMENT = LIST_VIEW + " :: deliveries";
  private static final String ORGANIZATION_ID_ATTRIBUTE = "organizationId";
  private static final String ORGANIZATION_NAME_ATTRIBUTE = "organizationName";
  private static final String ENDPOINT_ID_ATTRIBUTE = "endpointId";
  private static final String ENDPOINT_URL_ATTRIBUTE = "endpointUrl";

  private final ListWebhookDeliveriesForEndpointUseCase listDeliveries;
  private final ReplayWebhookDeliveryUseCase replayDelivery;
  private final ListWebhookEndpointsForOrganizationUseCase listEndpoints;
  private final OrganizationForPlatformAccountResolver organizationResolver;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  @SuppressWarnings("java:S107") // one parameter per collaborating port — same rationale as every
  // other multi-collaborator constructor in this codebase.
  public PlatformWebhookDeliveryController(
      final ListWebhookDeliveriesForEndpointUseCase listDeliveries,
      final ReplayWebhookDeliveryUseCase replayDelivery,
      final ListWebhookEndpointsForOrganizationUseCase listEndpoints,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.listDeliveries = listDeliveries;
    this.replayDelivery = replayDelivery;
    this.listEndpoints = listEndpoints;
    this.organizationResolver = organizationResolver;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  @GetMapping
  public String showList(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID endpointId,
      final Model model) {
    final UUID ownerPlatformAccountId =
        WebhookDashboardControllerSupport.requireCurrentPlatformAccount(
            currentPlatformAccount, request);
    final String organizationName =
        WebhookDashboardControllerSupport.requireOwnedOrganizationName(
            organizationResolver, organizationId, ownerPlatformAccountId);
    final WebhookEndpoint endpoint =
        WebhookDashboardControllerSupport.requireEndpointBelongsToOrganization(
            listEndpoints, organizationId, endpointId);

    populateHeaderModel(model, organizationId, organizationName, endpoint);
    populateDeliveriesModel(model, endpointId);
    return LIST_VIEW;
  }

  // Two exits (HTMX fragment vs. plain redirect on success) plus a third for the not-replayable
  // inline error — same rationale as every other dashboard controller's own identical "after a
  // mutation" suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/{deliveryId}/replay")
  public String replay(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID endpointId,
      @PathVariable final UUID deliveryId,
      final Model model) {
    final UUID ownerPlatformAccountId =
        WebhookDashboardControllerSupport.requireCurrentPlatformAccount(
            currentPlatformAccount, request);
    final String organizationName =
        WebhookDashboardControllerSupport.requireOwnedOrganizationName(
            organizationResolver, organizationId, ownerPlatformAccountId);
    final WebhookEndpoint endpoint =
        WebhookDashboardControllerSupport.requireEndpointBelongsToOrganization(
            listEndpoints, organizationId, endpointId);

    try {
      replayDelivery.handle(
          new ReplayWebhookDeliveryCommand(
              endpointId, deliveryId, AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final WebhookDeliveryNotFoundException _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final WebhookDeliveryNotReplayableException _) {
      model.addAttribute("notReplayableError", true);
      populateHeaderModel(model, organizationId, organizationName, endpoint);
      populateDeliveriesModel(model, endpointId);
      return WebhookDashboardControllerSupport.isHtmxRequest(request)
          ? DELIVERIES_FRAGMENT
          : LIST_VIEW;
    }

    if (WebhookDashboardControllerSupport.isHtmxRequest(request)) {
      populateHeaderModel(model, organizationId, organizationName, endpoint);
      populateDeliveriesModel(model, endpointId);
      return DELIVERIES_FRAGMENT;
    }
    return "redirect:/platform/dashboard/organizations/"
        + organizationId
        + "/webhook-endpoints/"
        + endpointId
        + "/deliveries";
  }

  private void populateHeaderModel(
      final Model model,
      final UUID organizationId,
      final String organizationName,
      final WebhookEndpoint endpoint) {
    model.addAttribute(ORGANIZATION_ID_ATTRIBUTE, organizationId);
    model.addAttribute(ORGANIZATION_NAME_ATTRIBUTE, organizationName);
    model.addAttribute(ENDPOINT_ID_ATTRIBUTE, endpoint.id());
    model.addAttribute(ENDPOINT_URL_ATTRIBUTE, endpoint.url());
  }

  private void populateDeliveriesModel(final Model model, final UUID endpointId) {
    model.addAttribute(
        "deliveries", listDeliveries.handle(new ListWebhookDeliveriesForEndpointQuery(endpointId)));
  }
}
