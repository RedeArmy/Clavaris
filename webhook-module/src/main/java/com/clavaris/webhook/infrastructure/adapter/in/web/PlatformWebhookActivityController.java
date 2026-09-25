package com.clavaris.webhook.infrastructure.adapter.in.web;

import com.clavaris.webhook.application.usecase.getwebhookdeliveryactivityfororganization.GetWebhookDeliveryActivityForOrganizationQuery;
import com.clavaris.webhook.application.usecase.getwebhookdeliveryactivityfororganization.GetWebhookDeliveryActivityForOrganizationUseCase;
import com.clavaris.webhook.application.usecase.getwebhookdeliveryactivityfororganization.WebhookDeliveryActivity;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Live UX request, 2026-09-25 (Clerk-parity Activity tab): headline success/failure counts plus a
 * simple hourly bar chart, both from {@link GetWebhookDeliveryActivityForOrganizationUseCase} — see
 * that use case's own Javadoc for the "Last 6 hours" window and the snapshot-not-history caveat.
 * {@code maxBucketTotal} is computed here, not in the template — Thymeleaf can express the
 * per-bucket height-as-a-percentage arithmetic fine, but finding the max across a list is exactly
 * the kind of computation that belongs in Java, not SpringEL.
 */
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/webhook-endpoints/activity")
public class PlatformWebhookActivityController {

  private static final String ACTIVITY_VIEW = "webhook/platform/webhook-activity";

  private final GetWebhookDeliveryActivityForOrganizationUseCase getActivity;
  private final OrganizationForPlatformAccountResolver organizationResolver;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  public PlatformWebhookActivityController(
      final GetWebhookDeliveryActivityForOrganizationUseCase getActivity,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getActivity = getActivity;
    this.organizationResolver = organizationResolver;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  @GetMapping
  public String showActivity(
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

    final WebhookDeliveryActivity activity =
        getActivity.handle(new GetWebhookDeliveryActivityForOrganizationQuery(organizationId));
    model.addAttribute("activity", activity);
    model.addAttribute(
        "maxBucketTotal",
        activity.hourlyBuckets().stream()
            .mapToLong(bucket -> bucket.successCount() + bucket.failureCount())
            .max()
            .orElse(0L));
    return ACTIVITY_VIEW;
  }
}
