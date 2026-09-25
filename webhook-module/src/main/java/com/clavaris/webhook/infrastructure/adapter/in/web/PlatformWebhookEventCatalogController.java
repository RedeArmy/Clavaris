package com.clavaris.webhook.infrastructure.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Live UX request, 2026-09-25 (Clerk-parity Event Catalog tab): a read-only reference page — every
 * event type from {@link KnownWebhookEventTypeOptions#CATALOG}, grouped by category, with its own
 * one-line description. No use case: this is static, web-layer-only reference data (see that
 * class's own Javadoc for why it carries no domain-level enforcement), the same reason {@code
 * event-type-picker.html} reads it directly via a SpringEL {@code T(...)} expression rather than a
 * model attribute a use case would populate.
 */
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping(
    "/platform/dashboard/organizations/{organizationId}/webhook-endpoints/event-catalog")
public class PlatformWebhookEventCatalogController {

  private static final String CATALOG_VIEW = "webhook/platform/webhook-event-catalog";

  private final OrganizationForPlatformAccountResolver organizationResolver;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  public PlatformWebhookEventCatalogController(
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.organizationResolver = organizationResolver;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  @GetMapping
  public String showCatalog(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      final Model model) {
    final UUID ownerPlatformAccountId =
        WebhookDashboardControllerSupport.requireCurrentPlatformAccount(
            currentPlatformAccount, request);
    final String organizationName =
        WebhookDashboardControllerSupport.requireOwnedOrganizationName(
            organizationResolver, organizationId, ownerPlatformAccountId);
    model.addAttribute("organizationId", organizationId);
    model.addAttribute("organizationName", organizationName);
    return CATALOG_VIEW;
  }
}
