package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.listoauthclients.ListOAuthClientsUseCase;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.OrganizationNotFoundException;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientCommand;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientResult;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientUseCase;
import com.clavaris.common.domain.model.AuditActor;
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
 * ADR-0025: the dashboard's own real {@code OAuthClient} registration/listing — the end-user OIDC
 * login client registration TD-FUT-032 named as the genuine remaining gap once Secret Key
 * management (a different concept, {@code OrganizationClient}) shipped. See {@code
 * PlatformOrganizationClientController}'s own Javadoc for the general shape this controller
 * mirrors; the differences below are deliberate, not oversights.
 *
 * <p>Every write here goes through the exact same {@link RegisterOAuthClientUseCase} the REST admin
 * API already exposes ({@code POST /api/v1/admin/organizations/{organizationId}/clients}) — this
 * controller adds a second, session-authenticated {@link AuditActor#platformAccount} caller, same
 * widening {@code RegisterOAuthClientCommand}'s own Javadoc documents. {@link
 * ListOAuthClientsUseCase} and the {@code findAllByOrganizationId} method it depends on are new —
 * see that use case's own Javadoc.
 *
 * <p>{@code organizationId} resolves through the already-shared {@link
 * OrganizationForPlatformAccountResolver} (client-registry-module's own copy, bridged in {@code
 * app}) — same anti-enumeration posture as every other dashboard controller in this module.
 *
 * <p>Unlike {@code PlatformOrganizationClientController}, this controller has no
 * deactivate/rotate-secret actions: {@code OAuthClient} itself has no {@code active} flag and no
 * domain-level secret-rotation method today (confirmed: neither exists on the domain class, unlike
 * {@code OrganizationClient}'s own {@code deactivate()}/{@code rotateSecret(...)}) — adding either
 * would be a domain change, not a dashboard-wiring one, and is out of scope for this increment.
 * Same "never redirect on a one-time-secret create" exception as {@code
 * PlatformOrganizationClientController#create} — {@link
 * RegisterOAuthClientResult#rawClientSecret()} has nowhere safe to travel through a redirect
 * either.
 */
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/oauth-clients")
public class PlatformOAuthClientController {

  private static final String LIST_VIEW = "clientregistry/platform/organization-oauth-clients";
  private static final String CLIENTS_FRAGMENT = LIST_VIEW + " :: clients";
  private static final String CREATE_FORM_ATTRIBUTE = "createForm";
  private static final String ORGANIZATION_ID_ATTRIBUTE = "organizationId";
  private static final String ORGANIZATION_NAME_ATTRIBUTE = "organizationName";
  private static final String GRANT_TYPE_OPTIONS_ATTRIBUTE = "grantTypeOptions";

  // HTMX's own request header (https://htmx.org/reference/#request_headers) — same convention as
  // every other dashboard controller's own identical constant.
  private static final String HX_REQUEST_HEADER = "HX-Request";

  private final RegisterOAuthClientUseCase registerClient;
  private final ListOAuthClientsUseCase listClients;
  private final OrganizationForPlatformAccountResolver organizationResolver;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  public PlatformOAuthClientController(
      final RegisterOAuthClientUseCase registerClient,
      final ListOAuthClientsUseCase listClients,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.registerClient = registerClient;
    this.listClients = listClients;
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
    model.addAttribute(CREATE_FORM_ATTRIBUTE, new RegisterOAuthClientForm());
    populateClientsModel(model, organizationId);
    return LIST_VIEW;
  }

  // Never returns "redirect:" — see this class's own Javadoc for why a one-time secret can't
  // safely travel through one. PMD.OnlyOneReturn: create/error each need their own exit, same
  // rationale as PlatformOrganizationClientController#create's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String create(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute(CREATE_FORM_ATTRIBUTE) final RegisterOAuthClientForm form,
      final BindingResult bindingResult,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final String organizationName =
        requireOwnedOrganizationName(organizationId, ownerPlatformAccountId);
    populateHeaderModel(model, organizationId, organizationName);

    if (bindingResult.hasErrors()) {
      populateClientsModel(model, organizationId);
      return isHtmxRequest(request) ? CLIENTS_FRAGMENT : LIST_VIEW;
    }

    final RegisterOAuthClientResult result;
    try {
      result =
          registerClient.handle(
              new RegisterOAuthClientCommand(
                  organizationId,
                  form.parseRedirectUris(),
                  form.getAllowedGrantTypes(),
                  form.parseAllowedScopes(),
                  form.isRequireConsent(),
                  form.parsePostLogoutRedirectUris(),
                  AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final OrganizationNotFoundException _) {
      // Not expected on this path — requireOwnedOrganizationName above already confirmed
      // organizationId exists — but a loud 404 is still safer than assuming that can never race
      // with a concurrent deletion.
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    model.addAttribute("justRegisteredRawSecret", result.rawClientSecret());
    model.addAttribute("justRegisteredClientId", result.client().clientId());
    model.addAttribute(CREATE_FORM_ATTRIBUTE, new RegisterOAuthClientForm());
    populateClientsModel(model, organizationId);
    return isHtmxRequest(request) ? CLIENTS_FRAGMENT : LIST_VIEW;
  }

  private void populateHeaderModel(
      final Model model, final UUID organizationId, final String organizationName) {
    model.addAttribute(ORGANIZATION_ID_ATTRIBUTE, organizationId);
    model.addAttribute(ORGANIZATION_NAME_ATTRIBUTE, organizationName);
    model.addAttribute(GRANT_TYPE_OPTIONS_ATTRIBUTE, OAuthGrantTypeOptions.DASHBOARD_OPTIONS);
  }

  private void populateClientsModel(final Model model, final UUID organizationId) {
    model.addAttribute("clients", listClients.handle(organizationId));
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
