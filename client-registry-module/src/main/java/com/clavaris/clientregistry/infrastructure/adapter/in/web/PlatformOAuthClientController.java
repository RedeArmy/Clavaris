package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.deactivateoauthclient.DeactivateOAuthClientCommand;
import com.clavaris.clientregistry.application.usecase.deactivateoauthclient.DeactivateOAuthClientUseCase;
import com.clavaris.clientregistry.application.usecase.listoauthclients.ListOAuthClientsUseCase;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.OrganizationNotFoundException;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientCommand;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientResult;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientUseCase;
import com.clavaris.clientregistry.application.usecase.rotateoauthclientsecret.RotateOAuthClientSecretCommand;
import com.clavaris.clientregistry.application.usecase.rotateoauthclientsecret.RotateOAuthClientSecretResult;
import com.clavaris.clientregistry.application.usecase.rotateoauthclientsecret.RotateOAuthClientSecretUseCase;
import com.clavaris.clientregistry.domain.model.OAuthClient;
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
 * ADR-0025: the dashboard's own real {@code OAuthClient} registration/listing/deactivation/
 * secret-rotation — the end-user OIDC login client registration TD-FUT-032 named as the genuine
 * remaining gap once Secret Key management (a different concept, {@code OrganizationClient})
 * shipped. See {@code PlatformOrganizationClientController}'s own Javadoc for the general shape
 * this controller mirrors; the differences below are deliberate, not oversights.
 *
 * <p>Every write here goes through the exact same use cases the REST admin API already exposes
 * ({@link RegisterOAuthClientUseCase} — {@code POST
 * /api/v1/admin/organizations/{organizationId}/clients}) plus two genuinely new ones this increment
 * adds, {@link DeactivateOAuthClientUseCase} and {@link RotateOAuthClientSecretUseCase} (SDE-III
 * review, 2026-09-11: {@code OAuthClient} itself gained an {@code active} flag and a {@code
 * rotateSecret(...)} method, closing the domain-layer gap an earlier pass of this controller had
 * deliberately left open — see {@code OAuthClient#deactivate}/{@code OAuthClient#rotateSecret}'s
 * own Javadoc). Every write here — create included — carries a second, session-authenticated {@link
 * AuditActor#platformAccount} caller, same widening {@code RegisterOAuthClientCommand}'s own
 * Javadoc documents. {@link ListOAuthClientsUseCase} and the {@code findAllByOrganizationId} method
 * it depends on are new — see that use case's own Javadoc.
 *
 * <p>{@code organizationId} resolves through the already-shared {@link
 * OrganizationForPlatformAccountResolver} (client-registry-module's own copy, bridged in {@code
 * app}) — same anti-enumeration posture as every other dashboard controller in this module. Neither
 * {@link DeactivateOAuthClientCommand} nor {@link RotateOAuthClientSecretCommand} carries an {@code
 * organizationId} of its own (both key off the client's own {@code clientId} string, same shape as
 * their {@code OrganizationClient} siblings) — this controller resolves the target {@link
 * OAuthClient} via the already-organizationId-scoped {@link ListOAuthClientsUseCase} first, so a
 * {@code clientId} belonging to a different Organization 404s before either mutating use case is
 * ever called, not after — same pattern {@code PlatformOrganizationClientController} already
 * established.
 *
 * <p>Unlike {@code PlatformOrganizationClientController}'s own deactivate, deactivation here still
 * keeps the usual redirect-on-success shape (no secret involved); create and rotate-secret never
 * return {@code "redirect:"} even for a plain (non-HTMX) form submit — {@link
 * RegisterOAuthClientResult#rawClientSecret()}/{@link RotateOAuthClientSecretResult#rawSecret()}
 * are shown exactly once and have nowhere safe to travel through a redirect (never a URL query
 * string, browser history, or server access log).
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
  private final DeactivateOAuthClientUseCase deactivateClient;
  private final RotateOAuthClientSecretUseCase rotateClientSecret;
  private final OrganizationForPlatformAccountResolver organizationResolver;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  @SuppressWarnings("java:S107") // one parameter per collaborating port — same rationale as every
  // other multi-collaborator constructor in this codebase.
  public PlatformOAuthClientController(
      final RegisterOAuthClientUseCase registerClient,
      final ListOAuthClientsUseCase listClients,
      final DeactivateOAuthClientUseCase deactivateClient,
      final RotateOAuthClientSecretUseCase rotateClientSecret,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.registerClient = registerClient;
    this.listClients = listClients;
    this.deactivateClient = deactivateClient;
    this.rotateClientSecret = rotateClientSecret;
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

  // Two exits (HTMX fragment vs. plain redirect) — same rationale as every other dashboard
  // controller's own identical "after a mutation succeeds" suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/{clientId}/deactivate")
  public String deactivate(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final String clientId,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final String organizationName =
        requireOwnedOrganizationName(organizationId, ownerPlatformAccountId);
    requireClientBelongsToOrganization(organizationId, clientId);

    deactivateClient.handle(
        new DeactivateOAuthClientCommand(
            clientId, AuditActor.platformAccount(ownerPlatformAccountId)));

    if (isHtmxRequest(request)) {
      populateHeaderModel(model, organizationId, organizationName);
      model.addAttribute(CREATE_FORM_ATTRIBUTE, new RegisterOAuthClientForm());
      populateClientsModel(model, organizationId);
      return CLIENTS_FRAGMENT;
    }
    return "redirect:/platform/dashboard/organizations/" + organizationId + "/oauth-clients";
  }

  // Never returns "redirect:" — same rationale as create() above.
  @PostMapping("/{clientId}/rotate-secret")
  public String rotateSecret(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final String clientId,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final String organizationName =
        requireOwnedOrganizationName(organizationId, ownerPlatformAccountId);
    requireClientBelongsToOrganization(organizationId, clientId);

    final RotateOAuthClientSecretResult result =
        rotateClientSecret.handle(
            new RotateOAuthClientSecretCommand(
                clientId, AuditActor.platformAccount(ownerPlatformAccountId)));

    populateHeaderModel(model, organizationId, organizationName);
    model.addAttribute("justRegisteredRawSecret", result.rawSecret());
    model.addAttribute("justRegisteredClientId", result.clientId());
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

  // The anti-enumeration check DeactivateOAuthClientCommand/RotateOAuthClientSecretCommand can't
  // do themselves — neither carries an organizationId, both key off clientId alone. Reuses the
  // already-organizationId-scoped ListOAuthClientsUseCase rather than adding a new "get one
  // client" port, so a clientId belonging to a different Organization 404s before the mutating
  // use case ever runs. Same pattern PlatformOrganizationClientController's own identical method
  // already established.
  private void requireClientBelongsToOrganization(
      final UUID organizationId, final String clientId) {
    final boolean belongsHere =
        listClients.handle(organizationId).stream()
            .map(OAuthClient::clientId)
            .anyMatch(clientId::equals);
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
