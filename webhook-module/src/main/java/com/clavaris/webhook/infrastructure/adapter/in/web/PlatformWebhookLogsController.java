package com.clavaris.webhook.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.webhook.application.usecase.listwebhookdeliveriesfororganizationpaged.ListWebhookDeliveriesForOrganizationPagedQuery;
import com.clavaris.webhook.application.usecase.listwebhookdeliveriesfororganizationpaged.ListWebhookDeliveriesForOrganizationPagedUseCase;
import com.clavaris.webhook.application.usecase.listwebhookendpointsfororganization.ListWebhookEndpointsForOrganizationQuery;
import com.clavaris.webhook.application.usecase.listwebhookendpointsfororganization.ListWebhookEndpointsForOrganizationUseCase;
import com.clavaris.webhook.application.usecase.replaywebhookdelivery.ReplayWebhookDeliveryCommand;
import com.clavaris.webhook.application.usecase.replaywebhookdelivery.ReplayWebhookDeliveryUseCase;
import com.clavaris.webhook.application.usecase.replaywebhookdelivery.WebhookDeliveryNotFoundException;
import com.clavaris.webhook.application.usecase.replaywebhookdelivery.WebhookDeliveryNotReplayableException;
import com.clavaris.webhook.domain.model.WebhookDelivery;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * Live UX request, 2026-09-25 (Clerk-parity org-wide Logs tab): every delivery across every one of
 * an Organization's own endpoints — {@code PlatformWebhookDeliveryController}'s own per-endpoint
 * Deliveries tab is a drill-down from one endpoint's own detail page; this is the org-wide sibling
 * Clerk's own "Logs" tab is. Each row's own endpoint URL is resolved via one {@link
 * ListWebhookEndpointsForOrganizationUseCase} call per page render — the Organization's own full
 * endpoint list, never more than {@code
 * RegisterWebhookEndpointService#MAX_ENDPOINTS_PER_ORGANIZATION} rows (BR-WEBHOOK-08) — not a
 * repository injected directly into this controller (this module's own use-case-per-feature
 * convention) and not one query per delivery row.
 */
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/webhook-endpoints/logs")
public class PlatformWebhookLogsController {

  private static final String LOGS_VIEW = "webhook/platform/webhook-logs";
  private static final String LOGS_FRAGMENT = LOGS_VIEW + " :: logs";

  private final ListWebhookDeliveriesForOrganizationPagedUseCase listDeliveriesPaged;
  private final ReplayWebhookDeliveryUseCase replayDelivery;
  private final ListWebhookEndpointsForOrganizationUseCase listEndpoints;
  private final OrganizationForPlatformAccountResolver organizationResolver;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  @SuppressWarnings("java:S107") // one parameter per collaborating port — same rationale as every
  // other multi-collaborator constructor in this codebase.
  public PlatformWebhookLogsController(
      final ListWebhookDeliveriesForOrganizationPagedUseCase listDeliveriesPaged,
      final ReplayWebhookDeliveryUseCase replayDelivery,
      final ListWebhookEndpointsForOrganizationUseCase listEndpoints,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.listDeliveriesPaged = listDeliveriesPaged;
    this.replayDelivery = replayDelivery;
    this.listEndpoints = listEndpoints;
    this.organizationResolver = organizationResolver;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @GetMapping
  public String showList(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @RequestParam(required = false) final String after,
      @RequestParam(required = false) final String before,
      final Model model) {
    final UUID ownerPlatformAccountId =
        WebhookDashboardControllerSupport.requireCurrentPlatformAccount(
            currentPlatformAccount, request);
    final String organizationName =
        WebhookDashboardControllerSupport.requireOwnedOrganizationName(
            organizationResolver, organizationId, ownerPlatformAccountId);
    populateHeaderModel(model, organizationId, organizationName);
    populateDeliveriesModel(model, organizationId, KeysetPageRequest.fromCursors(after, before));
    if (WebhookDashboardControllerSupport.isHtmxRequest(request)) {
      return LOGS_FRAGMENT;
    }
    return LOGS_VIEW;
  }

  // Two exits (HTMX fragment vs. plain redirect on success) plus a third for the not-replayable
  // inline error — same rationale as PlatformWebhookDeliveryController's own identical
  // suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/{endpointId}/{deliveryId}/replay")
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

    try {
      replayDelivery.handle(
          new ReplayWebhookDeliveryCommand(
              endpointId, deliveryId, AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final WebhookDeliveryNotFoundException _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final WebhookDeliveryNotReplayableException _) {
      model.addAttribute("notReplayableError", true);
      populateHeaderModel(model, organizationId, organizationName);
      populateDeliveriesModel(model, organizationId, KeysetPageRequest.first());
      return WebhookDashboardControllerSupport.isHtmxRequest(request) ? LOGS_FRAGMENT : LOGS_VIEW;
    }

    if (WebhookDashboardControllerSupport.isHtmxRequest(request)) {
      populateHeaderModel(model, organizationId, organizationName);
      populateDeliveriesModel(model, organizationId, KeysetPageRequest.first());
      return LOGS_FRAGMENT;
    }
    return "redirect:/platform/dashboard/organizations/"
        + organizationId
        + "/webhook-endpoints/logs";
  }

  private void populateHeaderModel(
      final Model model, final UUID organizationId, final String organizationName) {
    model.addAttribute("organizationId", organizationId);
    model.addAttribute("organizationName", organizationName);
  }

  private void populateDeliveriesModel(
      final Model model, final UUID organizationId, final KeysetPageRequest pageRequest) {
    final KeysetPage<WebhookDelivery> deliveriesPage =
        listDeliveriesPaged.handle(
            new ListWebhookDeliveriesForOrganizationPagedQuery(organizationId, pageRequest));
    final Map<UUID, String> endpointUrlsByEndpointId =
        listEndpoints.handle(new ListWebhookEndpointsForOrganizationQuery(organizationId)).stream()
            .collect(Collectors.toMap(WebhookEndpoint::id, WebhookEndpoint::url));
    model.addAttribute("deliveries", deliveriesPage.content());
    model.addAttribute("deliveriesPage", deliveriesPage);
    model.addAttribute("endpointUrlsByEndpointId", endpointUrlsByEndpointId);
  }
}
