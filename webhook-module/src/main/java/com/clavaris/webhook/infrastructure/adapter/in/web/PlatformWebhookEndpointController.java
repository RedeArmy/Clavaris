package com.clavaris.webhook.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.webhook.application.usecase.activatewebhookendpoint.ActivateWebhookEndpointCommand;
import com.clavaris.webhook.application.usecase.activatewebhookendpoint.ActivateWebhookEndpointUseCase;
import com.clavaris.webhook.application.usecase.deactivatewebhookendpoint.DeactivateWebhookEndpointCommand;
import com.clavaris.webhook.application.usecase.deactivatewebhookendpoint.DeactivateWebhookEndpointUseCase;
import com.clavaris.webhook.application.usecase.listwebhookendpointsfororganization.ListWebhookEndpointsForOrganizationQuery;
import com.clavaris.webhook.application.usecase.listwebhookendpointsfororganization.ListWebhookEndpointsForOrganizationUseCase;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.OrganizationNotFoundException;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.RegisterWebhookEndpointCommand;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.RegisterWebhookEndpointResult;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.RegisterWebhookEndpointUseCase;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.UnsafeWebhookUrlException;
import com.clavaris.webhook.application.usecase.rotatewebhookendpointsecret.RotateWebhookEndpointSecretCommand;
import com.clavaris.webhook.application.usecase.rotatewebhookendpointsecret.RotateWebhookEndpointSecretResult;
import com.clavaris.webhook.application.usecase.rotatewebhookendpointsecret.RotateWebhookEndpointSecretUseCase;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * ADR-0025, ADR-0007: the dashboard's own webhook endpoint (subscription) management — register,
 * list, deactivate, reactivate, and rotate the signing secret of an Organization's own {@code
 * WebhookEndpoint}s. Every write here goes through the exact same use cases the REST admin API
 * already exposes ({@link RegisterWebhookEndpointUseCase}, {@link
 * DeactivateWebhookEndpointUseCase}, {@link ActivateWebhookEndpointUseCase}, {@link
 * RotateWebhookEndpointSecretUseCase}) plus the already-organizationId-scoped {@link
 * ListWebhookEndpointsForOrganizationUseCase} — this controller adds a second,
 * session-authenticated {@link AuditActor#platformAccount} caller, not a second implementation. See
 * {@code RegisterWebhookEndpointCommand}'s own Javadoc for why this widening needed no restriction
 * to correct, unlike its Secret Key/OAuth Client siblings.
 *
 * <p>{@code organizationId} resolves through {@link OrganizationForPlatformAccountResolver} — never
 * a bare repository call — so an organizationId this {@code PlatformAccount} doesn't own resolves
 * identically to "doesn't exist" (a 404), same anti-enumeration posture as every other dashboard
 * controller in this codebase. Neither {@link DeactivateWebhookEndpointCommand}, {@link
 * ActivateWebhookEndpointCommand}, nor {@link RotateWebhookEndpointSecretCommand} carries an {@code
 * organizationId} of its own (all three key off the endpoint's own {@code endpointId} alone) — this
 * controller resolves the target {@link WebhookEndpoint} via the already-organizationId-scoped
 * {@link ListWebhookEndpointsForOrganizationUseCase} first, so an {@code endpointId} belonging to a
 * different Organization 404s before any mutating use case is ever called, not after.
 *
 * <p>Unlike Secret Keys/OAuth Clients, this endpoint has a genuine, independent {@code :activate}
 * action reversing {@code :deactivate} — both reachable from this page, since {@code
 * WebhookEndpoint} itself (unlike {@code OrganizationClient}/{@code OAuthClient}) supports
 * reactivation, not just a one-way deactivate. Register/rotate-secret never return {@code
 * "redirect:"} even for a plain (non-HTMX) form submit — {@link
 * RegisterWebhookEndpointResult#rawSigningSecret()}/{@link
 * RotateWebhookEndpointSecretResult#rawNewSigningSecret()} are shown exactly once and have nowhere
 * safe to travel through a redirect; deactivate/activate carry no secret, so both keep this
 * codebase's normal "success redirects, HTMX gets a fragment" convention.
 */
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/webhook-endpoints")
public class PlatformWebhookEndpointController {

  private static final String LIST_VIEW = "webhook/platform/organization-webhook-endpoints";
  private static final String ENDPOINTS_FRAGMENT = LIST_VIEW + " :: endpoints";
  private static final String CREATE_FORM_ATTRIBUTE = "createForm";
  private static final String ORGANIZATION_ID_ATTRIBUTE = "organizationId";
  private static final String ORGANIZATION_NAME_ATTRIBUTE = "organizationName";
  private static final String EVENT_TYPE_OPTIONS_ATTRIBUTE = "eventTypeOptions";

  // HTMX's own request header (https://htmx.org/reference/#request_headers) — same convention as
  // every other dashboard controller's own identical constant.
  private static final String HX_REQUEST_HEADER = "HX-Request";

  private final RegisterWebhookEndpointUseCase registerEndpoint;
  private final ListWebhookEndpointsForOrganizationUseCase listEndpoints;
  private final DeactivateWebhookEndpointUseCase deactivateEndpoint;
  private final ActivateWebhookEndpointUseCase activateEndpoint;
  private final RotateWebhookEndpointSecretUseCase rotateEndpointSecret;
  private final OrganizationForPlatformAccountResolver organizationResolver;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  @SuppressWarnings("java:S107") // one parameter per collaborating port — same rationale as every
  // other multi-collaborator constructor in this codebase.
  public PlatformWebhookEndpointController(
      final RegisterWebhookEndpointUseCase registerEndpoint,
      final ListWebhookEndpointsForOrganizationUseCase listEndpoints,
      final DeactivateWebhookEndpointUseCase deactivateEndpoint,
      final ActivateWebhookEndpointUseCase activateEndpoint,
      final RotateWebhookEndpointSecretUseCase rotateEndpointSecret,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.registerEndpoint = registerEndpoint;
    this.listEndpoints = listEndpoints;
    this.deactivateEndpoint = deactivateEndpoint;
    this.activateEndpoint = activateEndpoint;
    this.rotateEndpointSecret = rotateEndpointSecret;
    this.organizationResolver = organizationResolver;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  @GetMapping
  public String showList(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final String organizationName =
        requireOwnedOrganizationName(organizationId, ownerPlatformAccountId);
    populateHeaderModel(model, organizationId, organizationName);
    model.addAttribute(CREATE_FORM_ATTRIBUTE, new RegisterWebhookEndpointForm());
    populateEndpointsModel(model, organizationId);
    return LIST_VIEW;
  }

  // Never returns "redirect:" — see this class's own Javadoc for why a one-time secret can't
  // safely travel through one. PMD.OnlyOneReturn: create/validation-error/unsafe-url each need
  // their own exit, same rationale as every other handler in this codebase.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String create(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute(CREATE_FORM_ATTRIBUTE) final RegisterWebhookEndpointForm form,
      final BindingResult bindingResult,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final String organizationName =
        requireOwnedOrganizationName(organizationId, ownerPlatformAccountId);
    populateHeaderModel(model, organizationId, organizationName);

    if (bindingResult.hasErrors()) {
      populateEndpointsModel(model, organizationId);
      return isHtmxRequest(request) ? ENDPOINTS_FRAGMENT : LIST_VIEW;
    }

    final RegisterWebhookEndpointResult result;
    try {
      result =
          registerEndpoint.handle(
              new RegisterWebhookEndpointCommand(
                  organizationId,
                  form.getUrl(),
                  form.getDescription(),
                  form.getSubscribedEventTypes(),
                  AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final OrganizationNotFoundException _) {
      // Not expected on this path — requireOwnedOrganizationName above already confirmed
      // organizationId exists — but a loud 404 is still safer than assuming that can never race
      // with a concurrent deletion.
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final UnsafeWebhookUrlException _) {
      // TD-SEC-053: surfaced as a form error, not a raw 400 — the REST API's own equivalent
      // caller (an operator scripting against the admin API) gets a bare status code; a human
      // filling out this form needs to see why their submission was rejected.
      model.addAttribute("unsafeWebhookUrlError", true);
      model.addAttribute(CREATE_FORM_ATTRIBUTE, form);
      populateEndpointsModel(model, organizationId);
      return isHtmxRequest(request) ? ENDPOINTS_FRAGMENT : LIST_VIEW;
    }

    model.addAttribute("justRegisteredRawSecret", result.rawSigningSecret());
    model.addAttribute("justRegisteredEndpointId", result.endpoint().id());
    model.addAttribute(CREATE_FORM_ATTRIBUTE, new RegisterWebhookEndpointForm());
    populateEndpointsModel(model, organizationId);
    return isHtmxRequest(request) ? ENDPOINTS_FRAGMENT : LIST_VIEW;
  }

  // Two exits (HTMX fragment vs. plain redirect) — same rationale as every other dashboard
  // controller's own identical "after a mutation succeeds" suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/{endpointId}/deactivate")
  public String deactivate(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID endpointId,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final String organizationName =
        requireOwnedOrganizationName(organizationId, ownerPlatformAccountId);
    requireEndpointBelongsToOrganization(organizationId, endpointId);

    deactivateEndpoint.handle(
        new DeactivateWebhookEndpointCommand(
            endpointId, AuditActor.platformAccount(ownerPlatformAccountId)));

    if (isHtmxRequest(request)) {
      populateHeaderModel(model, organizationId, organizationName);
      model.addAttribute(CREATE_FORM_ATTRIBUTE, new RegisterWebhookEndpointForm());
      populateEndpointsModel(model, organizationId);
      return ENDPOINTS_FRAGMENT;
    }
    return "redirect:/platform/dashboard/organizations/" + organizationId + "/webhook-endpoints";
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/{endpointId}/activate")
  public String activate(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID endpointId,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final String organizationName =
        requireOwnedOrganizationName(organizationId, ownerPlatformAccountId);
    requireEndpointBelongsToOrganization(organizationId, endpointId);

    activateEndpoint.handle(
        new ActivateWebhookEndpointCommand(
            endpointId, AuditActor.platformAccount(ownerPlatformAccountId)));

    if (isHtmxRequest(request)) {
      populateHeaderModel(model, organizationId, organizationName);
      model.addAttribute(CREATE_FORM_ATTRIBUTE, new RegisterWebhookEndpointForm());
      populateEndpointsModel(model, organizationId);
      return ENDPOINTS_FRAGMENT;
    }
    return "redirect:/platform/dashboard/organizations/" + organizationId + "/webhook-endpoints";
  }

  // Never returns "redirect:" — same rationale as create() above.
  @PostMapping("/{endpointId}/rotate-secret")
  public String rotateSecret(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID endpointId,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final String organizationName =
        requireOwnedOrganizationName(organizationId, ownerPlatformAccountId);
    requireEndpointBelongsToOrganization(organizationId, endpointId);

    final RotateWebhookEndpointSecretResult result =
        rotateEndpointSecret.handle(
            new RotateWebhookEndpointSecretCommand(
                endpointId, AuditActor.platformAccount(ownerPlatformAccountId)));

    populateHeaderModel(model, organizationId, organizationName);
    model.addAttribute("justRegisteredRawSecret", result.rawNewSigningSecret());
    model.addAttribute("justRegisteredEndpointId", result.endpoint().id());
    model.addAttribute(CREATE_FORM_ATTRIBUTE, new RegisterWebhookEndpointForm());
    populateEndpointsModel(model, organizationId);
    return isHtmxRequest(request) ? ENDPOINTS_FRAGMENT : LIST_VIEW;
  }

  private void populateHeaderModel(
      final Model model, final UUID organizationId, final String organizationName) {
    model.addAttribute(ORGANIZATION_ID_ATTRIBUTE, organizationId);
    model.addAttribute(ORGANIZATION_NAME_ATTRIBUTE, organizationName);
    model.addAttribute(
        EVENT_TYPE_OPTIONS_ATTRIBUTE, KnownWebhookEventTypeOptions.DASHBOARD_OPTIONS);
  }

  private void populateEndpointsModel(final Model model, final UUID organizationId) {
    model.addAttribute(
        "endpoints",
        listEndpoints.handle(new ListWebhookEndpointsForOrganizationQuery(organizationId)));
  }

  // The anti-enumeration check Deactivate/Activate/RotateWebhookEndpointSecretCommand can't do
  // themselves — none of the three carries an organizationId, all key off endpointId alone.
  // Reuses the already-organizationId-scoped ListWebhookEndpointsForOrganizationUseCase rather
  // than adding a new "get one endpoint" port, so an endpointId belonging to a different
  // Organization 404s before the mutating use case ever runs.
  private void requireEndpointBelongsToOrganization(
      final UUID organizationId, final UUID endpointId) {
    final boolean belongsHere =
        listEndpoints.handle(new ListWebhookEndpointsForOrganizationQuery(organizationId)).stream()
            .map(WebhookEndpoint::id)
            .anyMatch(endpointId::equals);
    if (!belongsHere) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  private String requireOwnedOrganizationName(
      final UUID organizationId, final UUID ownerPlatformAccountId) {
    return organizationResolver
        .resolveName(organizationId, ownerPlatformAccountId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private static boolean isHtmxRequest(final HttpServletRequest request) {
    return "true".equals(request.getHeader(HX_REQUEST_HEADER));
  }

  // Same rationale as every other dashboard controller's own identical method.
  private UUID requireCurrentPlatformAccount(final HttpServletRequest request) {
    return currentPlatformAccount
        .resolve(request)
        .orElseThrow(
            () -> new IllegalStateException("No authenticated PlatformAccount on this request"));
  }
}
