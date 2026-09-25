package com.clavaris.webhook.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.webhook.application.usecase.deletewebhookendpoint.DeleteWebhookEndpointCommand;
import com.clavaris.webhook.application.usecase.deletewebhookendpoint.DeleteWebhookEndpointUseCase;
import com.clavaris.webhook.application.usecase.deletewebhookendpoint.WebhookEndpointActiveException;
import com.clavaris.webhook.application.usecase.getwebhookendpointfororganization.GetWebhookEndpointForOrganizationUseCase;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointNotFoundException;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * Live UX request, 2026-09-25: permanent, irreversible deletion of a {@code WebhookEndpoint} — the
 * same two-step confirmation shape {@code client-registry-module}'s own {@code
 * PlatformDeleteOAuthClientController} already establishes (see that class's own Javadoc for the
 * full design rationale: a single-use, short-TTL server-side confirmation token defeats a same-site
 * replay the ordinary {@code _csrf} token alone doesn't). Unlike that precedent's
 * clientId-or-"delete" typed field, this one only accepts the literal word {@code "delete"} — a
 * {@code WebhookEndpoint} has no short, human-typeable natural key the way an {@code OAuthClient}'s
 * own {@code clientId} does (its URL is long and easy to mistype).
 *
 * <p>Only reachable while the endpoint is already deactivated ({@link
 * WebhookEndpointActiveException}) — enforced on both {@code GET} (redirects back to the detail
 * page for a still-active endpoint) and {@code POST} (the real enforcement — {@link
 * DeleteWebhookEndpointUseCase} itself, never trusting the GET-time check alone).
 */
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping(
    "/platform/dashboard/organizations/{organizationId}/webhook-endpoints/{endpointId}/delete")
public class PlatformDeleteWebhookEndpointController {

  private static final String CONFIRM_VIEW = "webhook/platform/delete-webhook-endpoint-confirm";
  private static final String CONFIRMATION_TOKEN_PARAM = "confirmationToken";

  private final GetWebhookEndpointForOrganizationUseCase getEndpoint;
  private final DeleteWebhookEndpointUseCase deleteEndpoint;
  private final OrganizationForPlatformAccountResolver organizationResolver;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  public PlatformDeleteWebhookEndpointController(
      final GetWebhookEndpointForOrganizationUseCase getEndpoint,
      final DeleteWebhookEndpointUseCase deleteEndpoint,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getEndpoint = getEndpoint;
    this.deleteEndpoint = deleteEndpoint;
    this.organizationResolver = organizationResolver;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  // PMD.OnlyOneReturn: the still-active redirect and the real confirm-page render are two
  // genuinely distinct exits, same rationale as PlatformDeleteOAuthClientController's own
  // identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @GetMapping
  public String showConfirmation(
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
    final WebhookEndpoint endpoint = requireOwnedEndpoint(organizationId, endpointId);
    if (endpoint.active()) {
      return "redirect:/platform/dashboard/organizations/"
          + organizationId
          + "/webhook-endpoints/"
          + endpointId;
    }
    populateConfirmModel(request, model, organizationId, organizationName, endpoint);
    return CONFIRM_VIEW;
  }

  // PMD.OnlyOneReturn: token-invalid/confirmation-mismatch/success each need their own exit, same
  // rationale as PlatformDeleteOAuthClientController's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String delete(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID endpointId,
      @RequestParam(CONFIRMATION_TOKEN_PARAM) final String confirmationToken,
      @RequestParam("confirmedText") final String confirmedText,
      final Model model) {
    final UUID ownerPlatformAccountId =
        WebhookDashboardControllerSupport.requireCurrentPlatformAccount(
            currentPlatformAccount, request);
    final String organizationName =
        WebhookDashboardControllerSupport.requireOwnedOrganizationName(
            organizationResolver, organizationId, ownerPlatformAccountId);
    final WebhookEndpoint endpoint = requireOwnedEndpoint(organizationId, endpointId);

    // Single-use — this call always invalidates the token, whether it matches or not, before
    // anything else below runs. See this class's own Javadoc.
    final boolean tokenValid =
        DeleteWebhookEndpointConfirmationTokens.consume(request, endpointId, confirmationToken);
    if (!tokenValid) {
      populateConfirmModel(request, model, organizationId, organizationName, endpoint);
      model.addAttribute("confirmationExpiredError", true);
      return CONFIRM_VIEW;
    }
    if (!"delete".equals(confirmedText.strip())) {
      populateConfirmModel(request, model, organizationId, organizationName, endpoint);
      model.addAttribute("confirmationMismatchError", true);
      return CONFIRM_VIEW;
    }

    try {
      deleteEndpoint.handle(
          new DeleteWebhookEndpointCommand(
              endpointId, AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final WebhookEndpointNotFoundException _) {
      // Not expected on this path — requireOwnedEndpoint above already confirmed endpointId
      // exists — but a loud 404 is still safer than assuming that can never race with a
      // concurrent delete.
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final WebhookEndpointActiveException _) {
      // Not expected either — the GET above already redirected away for an active endpoint — but
      // a loud 409 is safer than assuming that can never race with a concurrent reactivation.
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    }

    // The endpoint's own detail page no longer exists — back to the list, same "redirect to the
    // list after a destructive action" shape PlatformDeleteOAuthClientController's own identical
    // POST already establishes.
    return "redirect:/platform/dashboard/organizations/" + organizationId + "/webhook-endpoints";
  }

  private void populateConfirmModel(
      final HttpServletRequest request,
      final Model model,
      final UUID organizationId,
      final String organizationName,
      final WebhookEndpoint endpoint) {
    model.addAttribute("organizationId", organizationId);
    model.addAttribute("organizationName", organizationName);
    model.addAttribute("endpoint", endpoint);
    // Always a fresh token — on the very first GET, and again on any failed POST retry (whose own
    // token was already consumed above), same single-use guarantee either way.
    model.addAttribute(
        CONFIRMATION_TOKEN_PARAM,
        DeleteWebhookEndpointConfirmationTokens.issue(request, endpoint.id()));
  }

  private WebhookEndpoint requireOwnedEndpoint(final UUID organizationId, final UUID endpointId) {
    return WebhookDashboardControllerSupport.requireEndpointBelongsToOrganization(
        getEndpoint, organizationId, endpointId);
  }
}
