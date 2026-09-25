package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.deleteoauthclient.DeleteOAuthClientCommand;
import com.clavaris.clientregistry.application.usecase.deleteoauthclient.DeleteOAuthClientUseCase;
import com.clavaris.clientregistry.application.usecase.deleteoauthclient.OAuthClientActiveException;
import com.clavaris.clientregistry.application.usecase.deleteoauthclient.OAuthClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.getoauthclientfororganization.GetOAuthClientForOrganizationQuery;
import com.clavaris.clientregistry.application.usecase.getoauthclientfororganization.GetOAuthClientForOrganizationUseCase;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.common.domain.model.AuditActor;
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
 * Live UX request, 2026-09-24: permanent, irreversible deletion of an {@code OAuthClient} — the
 * second genuine two-step confirmation flow in this codebase, same shape {@code
 * organization.infrastructure.adapter.in.web.PlatformDeleteOrganizationController} already
 * establishes for Organization deletion (see that class's own Javadoc for the full design
 * rationale: a single-use, short-TTL server-side confirmation token defeats a same-site replay the
 * ordinary {@code _csrf} token alone doesn't, and a typed-confirmation field is a human-confidence
 * guard only, never the actual security control).
 *
 * <p><b>Deliberate widening beyond that precedent:</b> the typed field here accepts either the
 * literal word {@code "delete"} <em>or</em> the client's own {@code clientId} — Organization's own
 * flow only accepts one exact string (its current {@code name}). Requested explicitly by the user;
 * still just a human-confidence guard either way, not a narrowing of the real security control.
 *
 * <p>Only reachable while the client is already deactivated ({@link OAuthClientActiveException}) —
 * enforced on both {@code GET} (redirects back to the detail page for a still-active client, same
 * as the link to reach this page being hidden there in the first place) and {@code POST} (the real
 * enforcement — {@link DeleteOAuthClientUseCase} itself, never trusting the GET-time check alone).
 */
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping(
    "/platform/dashboard/organizations/{organizationId}/oauth-clients/{clientId}/delete")
public class PlatformDeleteOAuthClientController {

  private static final String CONFIRM_VIEW = "clientregistry/platform/delete-oauth-client-confirm";
  private static final String CONFIRMATION_TOKEN_PARAM = "confirmationToken";

  private final GetOAuthClientForOrganizationUseCase getClient;
  private final DeleteOAuthClientUseCase deleteClient;
  private final OrganizationForPlatformAccountResolver organizationResolver;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  public PlatformDeleteOAuthClientController(
      final GetOAuthClientForOrganizationUseCase getClient,
      final DeleteOAuthClientUseCase deleteClient,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getClient = getClient;
    this.deleteClient = deleteClient;
    this.organizationResolver = organizationResolver;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  // PMD.OnlyOneReturn: the still-active redirect and the real confirm-page render are two
  // genuinely distinct exits, same rationale as every other multi-outcome handler in this
  // codebase.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @GetMapping
  public String showConfirmation(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final String clientId,
      final Model model) {
    final DashboardControllerSupport.OwnedOrganization owned =
        DashboardControllerSupport.requireOwnedOrganization(
            request, organizationId, currentPlatformAccount, organizationResolver);
    final OAuthClient client = requireOwnedClient(organizationId, clientId);
    if (client.active()) {
      return "redirect:/platform/dashboard/organizations/"
          + organizationId
          + "/oauth-clients/"
          + clientId;
    }
    populateConfirmModel(request, model, owned.organizationName(), client);
    return CONFIRM_VIEW;
  }

  // PMD.OnlyOneReturn: token-invalid/confirmation-mismatch/success each need their own exit, same
  // rationale as PlatformDeleteOrganizationController's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String delete(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final String clientId,
      @RequestParam(CONFIRMATION_TOKEN_PARAM) final String confirmationToken,
      @RequestParam("confirmedText") final String confirmedText,
      final Model model) {
    final DashboardControllerSupport.OwnedOrganization owned =
        DashboardControllerSupport.requireOwnedOrganization(
            request, organizationId, currentPlatformAccount, organizationResolver);
    final OAuthClient client = requireOwnedClient(organizationId, clientId);

    // Single-use — this call always invalidates the token, whether it matches or not, before
    // anything else below runs. See this class's own Javadoc.
    final boolean tokenValid =
        DeleteOAuthClientConfirmationTokens.consume(request, clientId, confirmationToken);
    if (!tokenValid) {
      populateConfirmModel(request, model, owned.organizationName(), client);
      model.addAttribute("confirmationExpiredError", true);
      return CONFIRM_VIEW;
    }
    final String typed = confirmedText.strip();
    if (!"delete".equals(typed) && !client.clientId().equals(typed)) {
      populateConfirmModel(request, model, owned.organizationName(), client);
      model.addAttribute("confirmationMismatchError", true);
      return CONFIRM_VIEW;
    }

    try {
      deleteClient.handle(
          new DeleteOAuthClientCommand(
              clientId,
              organizationId,
              AuditActor.platformAccount(owned.ownerPlatformAccountId())));
    } catch (final OAuthClientNotFoundException _) {
      // Not expected on this path — requireOwnedClient above already confirmed clientId exists —
      // but a loud 404 is still safer than assuming that can never race with a concurrent delete.
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final OAuthClientActiveException _) {
      // Not expected either — the GET above already redirected away for an active client — but a
      // loud 409 is safer than assuming that can never race with a concurrent reactivation.
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    }

    // The client's own detail page no longer exists — back to the list, same "redirect to the
    // list after a destructive action" shape PlatformDeleteOrganizationController's own identical
    // POST already establishes.
    return "redirect:/platform/dashboard/organizations/" + organizationId + "/oauth-clients";
  }

  private void populateConfirmModel(
      final HttpServletRequest request,
      final Model model,
      final String organizationName,
      final OAuthClient client) {
    model.addAttribute("organizationId", client.organizationId());
    model.addAttribute("organizationName", organizationName);
    model.addAttribute("client", client);
    // Always a fresh token — on the very first GET, and again on any failed POST retry (whose own
    // token was already consumed above), same single-use guarantee either way.
    model.addAttribute(
        CONFIRMATION_TOKEN_PARAM,
        DeleteOAuthClientConfirmationTokens.issue(request, client.clientId()));
  }

  private OAuthClient requireOwnedClient(final UUID organizationId, final String clientId) {
    return getClient
        .handle(new GetOAuthClientForOrganizationQuery(clientId, organizationId))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }
}
