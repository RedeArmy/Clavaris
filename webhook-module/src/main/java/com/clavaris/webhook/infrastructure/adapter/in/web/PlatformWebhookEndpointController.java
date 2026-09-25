package com.clavaris.webhook.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.webhook.application.usecase.activatewebhookendpoint.ActivateWebhookEndpointCommand;
import com.clavaris.webhook.application.usecase.activatewebhookendpoint.ActivateWebhookEndpointUseCase;
import com.clavaris.webhook.application.usecase.deactivatewebhookendpoint.DeactivateWebhookEndpointCommand;
import com.clavaris.webhook.application.usecase.deactivatewebhookendpoint.DeactivateWebhookEndpointUseCase;
import com.clavaris.webhook.application.usecase.getwebhookendpointfororganization.GetWebhookEndpointForOrganizationUseCase;
import com.clavaris.webhook.application.usecase.listwebhookendpointsfororganizationpaged.ListWebhookEndpointsForOrganizationPagedQuery;
import com.clavaris.webhook.application.usecase.listwebhookendpointsfororganizationpaged.ListWebhookEndpointsForOrganizationPagedUseCase;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.OrganizationNotFoundException;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.RegisterWebhookEndpointCommand;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.RegisterWebhookEndpointResult;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.RegisterWebhookEndpointUseCase;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.UnsafeWebhookUrlException;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointLimitExceededException;
import com.clavaris.webhook.application.usecase.rotatewebhookendpointsecret.RotateWebhookEndpointSecretCommand;
import com.clavaris.webhook.application.usecase.rotatewebhookendpointsecret.RotateWebhookEndpointSecretResult;
import com.clavaris.webhook.application.usecase.rotatewebhookendpointsecret.RotateWebhookEndpointSecretUseCase;
import com.clavaris.webhook.application.usecase.updatewebhookendpointdescription.UpdateWebhookEndpointDescriptionCommand;
import com.clavaris.webhook.application.usecase.updatewebhookendpointdescription.UpdateWebhookEndpointDescriptionUseCase;
import com.clavaris.webhook.application.usecase.updatewebhookendpointeventtypes.UpdateWebhookEndpointEventTypesCommand;
import com.clavaris.webhook.application.usecase.updatewebhookendpointeventtypes.UpdateWebhookEndpointEventTypesUseCase;
import com.clavaris.webhook.application.usecase.updatewebhookendpointurl.UpdateWebhookEndpointUrlCommand;
import com.clavaris.webhook.application.usecase.updatewebhookendpointurl.UpdateWebhookEndpointUrlUseCase;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * ADR-0025, ADR-0007: the dashboard's own webhook endpoint (subscription) management — register,
 * list, view, deactivate, reactivate, rotate the signing secret of, and edit an Organization's own
 * {@code WebhookEndpoint}s. Every write here goes through the exact same use cases the REST admin
 * API already exposes — this controller adds a second, session-authenticated {@link
 * AuditActor#platformAccount} caller, not a second implementation.
 *
 * <p>Live UX request, 2026-09-25 (Clerk-parity master-detail redesign, same shape {@code
 * client-registry-module}'s own OAuth Client list/detail split already establishes): registration
 * moved to its own page ({@code showRegisterForm}/{@code register-webhook-endpoint.html}) instead
 * of sitting inline at the bottom of the list; every per-endpoint action
 * (deactivate/activate/rotate secret, plus the new URL/description/event-type edits below) moved
 * off the list's own row actions onto a new per-endpoint detail page ({@code showDetail}/{@code
 * organization-webhook-endpoint-detail.html}). The list itself keeps its existing HTMX-fragment
 * keyset pagination (unchanged); the detail page's own forms are plain (no {@code hx-post}) — a
 * single endpoint's own page has no sibling content an in-place swap would need to avoid
 * re-rendering.
 *
 * <p>{@code organizationId} resolves through {@link OrganizationForPlatformAccountResolver} — never
 * a bare repository call — so an organizationId this {@code PlatformAccount} doesn't own resolves
 * identically to "doesn't exist" (a 404), same anti-enumeration posture as every other dashboard
 * controller in this codebase. Deactivate/activate/rotate-secret/update-* commands carry no {@code
 * organizationId} of their own (all key off the endpoint's own {@code endpointId} alone) — this
 * controller resolves the target {@link WebhookEndpoint} via the O(1) {@code
 * GetWebhookEndpointForOrganizationUseCase} first (TD-PERF-026), so an {@code endpointId} belonging
 * to a different Organization 404s before any mutating use case is ever called, not after.
 *
 * <p>Register/rotate-secret/update-url never return {@code "redirect:"} on the path that shows a
 * one-time secret or re-displays a rejected value — {@link
 * RegisterWebhookEndpointResult#rawSigningSecret()}/{@link
 * RotateWebhookEndpointSecretResult#rawNewSigningSecret()} have nowhere safe to travel through a
 * redirect, and an SSRF-rejected URL needs its form re-displayed with the value the owner just
 * typed, not lost to a fresh GET.
 */
// PMD.ExcessiveImports/PMD.CouplingBetweenObjects: every import/collaborator here backs a real,
// distinct use case or form object this controller genuinely needs — same "wiring, not sprawl"
// reasoning this codebase's own comparable controllers already document.
// PMD.AvoidDuplicateLiterals: "PMD.OnlyOneReturn" is repeated once per handler method that
// legitimately needs it — same false-positive class ContentSecurityPolicyHeaderWriter's own
// identical class-level suppression already documents.
@SuppressWarnings({
  "PMD.LongVariable",
  "PMD.ExcessiveImports",
  "PMD.CouplingBetweenObjects",
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyMethods"
})
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/webhook-endpoints")
public class PlatformWebhookEndpointController {

  private static final String LIST_VIEW = "webhook/platform/organization-webhook-endpoints";
  private static final String ENDPOINTS_FRAGMENT = LIST_VIEW + " :: endpoints";
  private static final String REGISTER_VIEW = "webhook/platform/register-webhook-endpoint";
  private static final String DETAIL_VIEW = "webhook/platform/organization-webhook-endpoint-detail";
  private static final String CREATE_FORM_ATTRIBUTE = "createForm";
  private static final String ORGANIZATION_ID_ATTRIBUTE = "organizationId";
  private static final String ORGANIZATION_NAME_ATTRIBUTE = "organizationName";
  private static final String ENDPOINT_ATTRIBUTE = "endpoint";
  private static final String JUST_REGISTERED_RAW_SECRET_ATTRIBUTE = "justRegisteredRawSecret";

  private final RegisterWebhookEndpointUseCase registerEndpoint;
  private final GetWebhookEndpointForOrganizationUseCase getEndpoint;
  private final ListWebhookEndpointsForOrganizationPagedUseCase listEndpointsPaged;
  private final DeactivateWebhookEndpointUseCase deactivateEndpoint;
  private final ActivateWebhookEndpointUseCase activateEndpoint;
  private final RotateWebhookEndpointSecretUseCase rotateEndpointSecret;
  private final UpdateWebhookEndpointUrlUseCase updateEndpointUrl;
  private final UpdateWebhookEndpointDescriptionUseCase updateEndpointDescription;
  private final UpdateWebhookEndpointEventTypesUseCase updateEndpointEventTypes;
  private final OrganizationForPlatformAccountResolver organizationResolver;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  // java:S107/PMD.ExcessiveParameterList: one parameter per collaborating port — same rationale
  // as every other multi-collaborator constructor in this codebase. The three update-* use cases
  // added by this class's own live UX request, 2026-09-25 pushed the count from 8 to 11, past
  // PMD's own default threshold of 10.
  @SuppressWarnings({"java:S107", "PMD.ExcessiveParameterList"})
  public PlatformWebhookEndpointController(
      final RegisterWebhookEndpointUseCase registerEndpoint,
      final GetWebhookEndpointForOrganizationUseCase getEndpoint,
      final ListWebhookEndpointsForOrganizationPagedUseCase listEndpointsPaged,
      final DeactivateWebhookEndpointUseCase deactivateEndpoint,
      final ActivateWebhookEndpointUseCase activateEndpoint,
      final RotateWebhookEndpointSecretUseCase rotateEndpointSecret,
      final UpdateWebhookEndpointUrlUseCase updateEndpointUrl,
      final UpdateWebhookEndpointDescriptionUseCase updateEndpointDescription,
      final UpdateWebhookEndpointEventTypesUseCase updateEndpointEventTypes,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.registerEndpoint = registerEndpoint;
    this.getEndpoint = getEndpoint;
    this.listEndpointsPaged = listEndpointsPaged;
    this.deactivateEndpoint = deactivateEndpoint;
    this.activateEndpoint = activateEndpoint;
    this.rotateEndpointSecret = rotateEndpointSecret;
    this.updateEndpointUrl = updateEndpointUrl;
    this.updateEndpointDescription = updateEndpointDescription;
    this.updateEndpointEventTypes = updateEndpointEventTypes;
    this.organizationResolver = organizationResolver;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  // TD-PERF-020 (keyset revision, 2026-09-14): ?after=/?before= carry an opaque KeysetCursor
  // token — see organization-module's PlatformOrganizationDashboardController for the full
  // reasoning. This GET also branches on HX-Request — a pagination link is itself an hx-get, and
  // its hx-target can't safely receive a full HTML document.
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
    populateEndpointsModel(model, organizationId, KeysetPageRequest.fromCursors(after, before));
    if (WebhookDashboardControllerSupport.isHtmxRequest(request)) {
      return ENDPOINTS_FRAGMENT;
    }
    return LIST_VIEW;
  }

  @GetMapping("/new")
  public String showRegisterForm(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      final Model model) {
    final UUID ownerPlatformAccountId =
        WebhookDashboardControllerSupport.requireCurrentPlatformAccount(
            currentPlatformAccount, request);
    final String organizationName =
        WebhookDashboardControllerSupport.requireOwnedOrganizationName(
            organizationResolver, organizationId, ownerPlatformAccountId);
    populateHeaderModel(model, organizationId, organizationName);
    model.addAttribute(CREATE_FORM_ATTRIBUTE, new RegisterWebhookEndpointForm());
    return REGISTER_VIEW;
  }

  @GetMapping("/{endpointId}")
  public String showDetail(
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
            getEndpoint, organizationId, endpointId);
    populateHeaderModel(model, organizationId, organizationName);
    model.addAttribute(ENDPOINT_ATTRIBUTE, endpoint);
    return DETAIL_VIEW;
  }

  // Never returns "redirect:" on the rejected-registration paths — see this class's own Javadoc
  // for why a re-displayed form can't safely be a fresh GET either (the SSRF-rejected value the
  // owner just typed would be lost). PMD.OnlyOneReturn: create/validation-error/unsafe-url each
  // need their own exit, same rationale as every other handler in this codebase.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String create(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute(CREATE_FORM_ATTRIBUTE) final RegisterWebhookEndpointForm form,
      final BindingResult bindingResult,
      final Model model) {
    final UUID ownerPlatformAccountId =
        WebhookDashboardControllerSupport.requireCurrentPlatformAccount(
            currentPlatformAccount, request);
    final String organizationName =
        WebhookDashboardControllerSupport.requireOwnedOrganizationName(
            organizationResolver, organizationId, ownerPlatformAccountId);
    populateHeaderModel(model, organizationId, organizationName);

    if (bindingResult.hasErrors()) {
      return REGISTER_VIEW;
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
      return REGISTER_VIEW;
    } catch (final WebhookEndpointLimitExceededException _) {
      // BR-WEBHOOK-08: same "form error, not a bare status code" reasoning as the SSRF catch
      // above.
      model.addAttribute("webhookEndpointLimitExceededError", true);
      model.addAttribute(CREATE_FORM_ATTRIBUTE, form);
      return REGISTER_VIEW;
    }

    // Renders the new endpoint's own detail page directly — same "never redirect a one-time
    // secret" rule as rotateSecret() below, and the natural place to land now that registration
    // no longer happens inline on the list (see this class's own Javadoc).
    model.addAttribute(ENDPOINT_ATTRIBUTE, result.endpoint());
    model.addAttribute(JUST_REGISTERED_RAW_SECRET_ATTRIBUTE, result.rawSigningSecret());
    return DETAIL_VIEW;
  }

  @PostMapping("/{endpointId}/deactivate")
  public String deactivate(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID endpointId) {
    final UUID ownerPlatformAccountId =
        WebhookDashboardControllerSupport.requireCurrentPlatformAccount(
            currentPlatformAccount, request);
    WebhookDashboardControllerSupport.requireOwnedOrganizationName(
        organizationResolver, organizationId, ownerPlatformAccountId);
    WebhookDashboardControllerSupport.requireEndpointBelongsToOrganization(
        getEndpoint, organizationId, endpointId);

    deactivateEndpoint.handle(
        new DeactivateWebhookEndpointCommand(
            endpointId, AuditActor.platformAccount(ownerPlatformAccountId)));

    return redirectToDetail(organizationId, endpointId);
  }

  @PostMapping("/{endpointId}/activate")
  public String activate(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID endpointId) {
    final UUID ownerPlatformAccountId =
        WebhookDashboardControllerSupport.requireCurrentPlatformAccount(
            currentPlatformAccount, request);
    WebhookDashboardControllerSupport.requireOwnedOrganizationName(
        organizationResolver, organizationId, ownerPlatformAccountId);
    WebhookDashboardControllerSupport.requireEndpointBelongsToOrganization(
        getEndpoint, organizationId, endpointId);

    activateEndpoint.handle(
        new ActivateWebhookEndpointCommand(
            endpointId, AuditActor.platformAccount(ownerPlatformAccountId)));

    return redirectToDetail(organizationId, endpointId);
  }

  // Never returns "redirect:" — see this class's own Javadoc.
  @PostMapping("/{endpointId}/rotate-secret")
  public String rotateSecret(
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
    WebhookDashboardControllerSupport.requireEndpointBelongsToOrganization(
        getEndpoint, organizationId, endpointId);

    final RotateWebhookEndpointSecretResult result =
        rotateEndpointSecret.handle(
            new RotateWebhookEndpointSecretCommand(
                endpointId, AuditActor.platformAccount(ownerPlatformAccountId)));

    populateHeaderModel(model, organizationId, organizationName);
    model.addAttribute(ENDPOINT_ATTRIBUTE, result.endpoint());
    model.addAttribute(JUST_REGISTERED_RAW_SECRET_ATTRIBUTE, result.rawNewSigningSecret());
    return DETAIL_VIEW;
  }

  // Never returns "redirect:" on the rejected-URL path — same rationale as create() above: the
  // value the owner just typed would be lost to a fresh GET.
  @SuppressWarnings("PMD.OnlyOneReturn")
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
      populateHeaderModel(model, organizationId, organizationName);
      model.addAttribute(
          ENDPOINT_ATTRIBUTE,
          WebhookDashboardControllerSupport.requireEndpointBelongsToOrganization(
              getEndpoint, organizationId, endpointId));
      model.addAttribute("unsafeWebhookUrlError", true);
      return DETAIL_VIEW;
    }

    populateHeaderModel(model, organizationId, organizationName);
    model.addAttribute(ENDPOINT_ATTRIBUTE, updated);
    return redirectToDetail(organizationId, endpointId);
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

    return redirectToDetail(organizationId, endpointId);
  }

  // PMD.OnlyOneReturn: the empty-selection form-error exit and the real success redirect are two
  // genuinely distinct outcomes, same rationale as updateUrl()'s own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
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
      // and the "All Events" toggle both left unchecked).
      populateHeaderModel(model, organizationId, organizationName);
      model.addAttribute(ENDPOINT_ATTRIBUTE, existing);
      model.addAttribute("emptyEventTypesError", true);
      return DETAIL_VIEW;
    }

    updateEndpointEventTypes.handle(
        new UpdateWebhookEndpointEventTypesCommand(
            endpointId, subscribedEventTypes, AuditActor.platformAccount(ownerPlatformAccountId)));

    return redirectToDetail(organizationId, endpointId);
  }

  private String redirectToDetail(final UUID organizationId, final UUID endpointId) {
    return "redirect:/platform/dashboard/organizations/"
        + organizationId
        + "/webhook-endpoints/"
        + endpointId;
  }

  private void populateHeaderModel(
      final Model model, final UUID organizationId, final String organizationName) {
    model.addAttribute(ORGANIZATION_ID_ATTRIBUTE, organizationId);
    model.addAttribute(ORGANIZATION_NAME_ATTRIBUTE, organizationName);
  }

  private void populateEndpointsModel(
      final Model model, final UUID organizationId, final KeysetPageRequest pageRequest) {
    final KeysetPage<WebhookEndpoint> endpointsPage =
        listEndpointsPaged.handle(
            new ListWebhookEndpointsForOrganizationPagedQuery(organizationId, pageRequest));
    model.addAttribute("endpoints", endpointsPage.content());
    model.addAttribute("endpointsPage", endpointsPage);
  }
}
