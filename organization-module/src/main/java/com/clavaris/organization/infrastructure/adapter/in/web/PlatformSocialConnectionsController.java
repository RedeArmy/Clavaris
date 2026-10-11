package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.deleteorganizationsocialcredential.DeleteOrganizationSocialCredentialCommand;
import com.clavaris.organization.application.usecase.deleteorganizationsocialcredential.DeleteOrganizationSocialCredentialUseCase;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountQuery;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.getsocialconnections.GetSocialConnectionsUseCase;
import com.clavaris.organization.application.usecase.getsocialconnections.SocialConnection;
import com.clavaris.organization.application.usecase.getsocialconnections.SocialConnections;
import com.clavaris.organization.application.usecase.savesocialconnection.CredentialsRequiredException;
import com.clavaris.organization.application.usecase.savesocialconnection.CustomCredentialsNotAllowedException;
import com.clavaris.organization.application.usecase.savesocialconnection.IncompleteCredentialsException;
import com.clavaris.organization.application.usecase.savesocialconnection.OrganizationNotFoundException;
import com.clavaris.organization.application.usecase.savesocialconnection.SaveSocialConnectionUseCase;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.SocialProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Locale;
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
 * The "Connections" page under an Organization's Configure section: which social providers (Google
 * and GitHub) its sign-in offers, and, for a PRODUCTION Organization, the OAuth app credentials it
 * signs in through. {@code GET .../connections} lists the providers with their status; {@code GET
 * .../connections/{provider}} is one provider's page; {@code POST} on it saves the switch, the
 * credentials and the settings together.
 *
 * <p>Ownership is checked first, through {@link GetOrganizationForPlatformAccountUseCase}: an
 * Organization that is someone else's resolves exactly like one that does not exist (404), so the
 * page cannot be used to probe ids. A saved connection redirects back to its own page; a rejected
 * one shows that page again with what was typed (never the secret) and the reason.
 */
// PMD.LongVariable: names match what they are; PMD.OnlyOneReturn: each handler has a real second
// exit (a rejected save shows the page again), same as every sibling dashboard controller.
@SuppressWarnings({"PMD.LongVariable", "PMD.OnlyOneReturn", "PMD.TooManyMethods"})
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/connections")
public class PlatformSocialConnectionsController {

  private static final String LIST_VIEW = "organization/platform/organization-connections";
  private static final String DETAIL_VIEW = "organization/platform/organization-connection-detail";
  private static final String CONNECTIONS_PATH_PREFIX = "/platform/dashboard/organizations/";
  private static final String CLIENT_ID_FIELD = "clientId";
  private static final String CLIENT_SECRET_FIELD = "clientSecret";
  private static final String FORM_ATTRIBUTE = "connectionForm";

  private final GetOrganizationForPlatformAccountUseCase getOrganization;
  private final GetSocialConnectionsUseCase getConnections;
  private final SaveSocialConnectionUseCase saveConnection;
  private final DeleteOrganizationSocialCredentialUseCase deleteCredentials;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  public PlatformSocialConnectionsController(
      final GetOrganizationForPlatformAccountUseCase getOrganization,
      final GetSocialConnectionsUseCase getConnections,
      final SaveSocialConnectionUseCase saveConnection,
      final DeleteOrganizationSocialCredentialUseCase deleteCredentials,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getOrganization = getOrganization;
    this.getConnections = getConnections;
    this.saveConnection = saveConnection;
    this.deleteCredentials = deleteCredentials;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  @GetMapping
  public String list(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      final Model model) {
    final Organization organization = requireOwnedOrganization(request, organizationId);
    populateCommon(model, organization, getConnections.handle(organizationId));
    return LIST_VIEW;
  }

  @GetMapping("/{provider}")
  public String show(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final String provider,
      final Model model) {
    final Organization organization = requireOwnedOrganization(request, organizationId);
    final SocialProvider socialProvider = parseProvider(provider);
    final SocialConnection connection =
        connectionOf(organizationId, socialProvider, model, organization);
    model.addAttribute(FORM_ATTRIBUTE, formFrom(connection));
    return DETAIL_VIEW;
  }

  @PostMapping("/{provider}")
  public String save(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final String provider,
      @Valid @ModelAttribute(FORM_ATTRIBUTE) final SocialConnectionForm form,
      final BindingResult bindingResult,
      final Model model) {
    final UUID owner = requireCurrentPlatformAccount(request);
    final Organization organization = requireOwnedOrganization(owner, organizationId);
    final SocialProvider socialProvider = parseProvider(provider);

    if (!bindingResult.hasErrors()) {
      try {
        saveConnection.handle(
            form.toCommand(organizationId, socialProvider, AuditActor.platformAccount(owner)));
        return "redirect:" + connectionPath(organizationId, socialProvider) + "?saved";
      } catch (final OrganizationNotFoundException e) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found", e);
      } catch (final CredentialsRequiredException _) {
        bindingResult.rejectValue(
            CLIENT_ID_FIELD,
            "required",
            "Enter this provider's client ID and client secret to enable it");
      } catch (final IncompleteCredentialsException _) {
        bindingResult.rejectValue(
            CLIENT_SECRET_FIELD, "incomplete", "Enter both the client ID and the client secret");
      } catch (final CustomCredentialsNotAllowedException _) {
        bindingResult.rejectValue(
            CLIENT_ID_FIELD,
            "development",
            "A development Organization signs in through Clavaris's shared app");
      }
    }
    // The page again, with what was typed. The secret is cleared: it is never sent back to a page.
    form.setClientSecret(null);
    connectionOf(organizationId, socialProvider, model, organization);
    return DETAIL_VIEW;
  }

  @PostMapping("/{provider}/credentials/remove")
  public String removeCredentials(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final String provider) {
    final UUID owner = requireCurrentPlatformAccount(request);
    requireOwnedOrganization(owner, organizationId);
    final SocialProvider socialProvider = parseProvider(provider);
    deleteCredentials.handle(
        new DeleteOrganizationSocialCredentialCommand(
            organizationId, socialProvider, AuditActor.platformAccount(owner)));
    return "redirect:" + connectionPath(organizationId, socialProvider) + "?removed";
  }

  private SocialConnection connectionOf(
      final UUID organizationId,
      final SocialProvider provider,
      final Model model,
      final Organization organization) {
    final SocialConnections connections = getConnections.handle(organizationId);
    populateCommon(model, organization, connections);
    final SocialConnection connection =
        connections.connections().stream()
            .filter(candidate -> candidate.provider() == provider)
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    model.addAttribute("connection", connection);
    return connection;
  }

  private static void populateCommon(
      final Model model, final Organization organization, final SocialConnections connections) {
    model.addAttribute("organizationId", organization.id());
    model.addAttribute("organizationName", organization.name());
    model.addAttribute("production", connections.production());
    // A development Organization that was already promoted has a production one linked to it: its
    // connections are set up there, so the page points to it instead of offering to promote again.
    model.addAttribute(
        "linkedEnvironmentId", organization.linkedEnvironmentOrganizationId().orElse(null));
    model.addAttribute("connections", connections.connections());
  }

  private static SocialConnectionForm formFrom(final SocialConnection connection) {
    final SocialConnectionForm form = new SocialConnectionForm();
    form.setEnabled(connection.enabled());
    // Only the client id is ever shown again. The secret is not, which is why a blank one keeps it.
    form.setClientId(connection.clientId().orElse(""));
    form.setBlockEmailSubaddresses(connection.blockEmailSubaddresses());
    form.setAlwaysShowAccountSelector(connection.alwaysShowAccountSelector());
    return form;
  }

  private static String connectionPath(final UUID organizationId, final SocialProvider provider) {
    return CONNECTIONS_PATH_PREFIX
        + organizationId
        + "/connections/"
        + provider.name().toLowerCase(Locale.ROOT);
  }

  // The URL names a provider in lower case ("google"); anything else is not a page.
  private static SocialProvider parseProvider(final String provider) {
    try {
      return SocialProvider.valueOf(provider.toUpperCase(Locale.ROOT));
    } catch (final IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown provider", e);
    }
  }

  private Organization requireOwnedOrganization(
      final HttpServletRequest request, final UUID organizationId) {
    return requireOwnedOrganization(requireCurrentPlatformAccount(request), organizationId);
  }

  private Organization requireOwnedOrganization(final UUID owner, final UUID organizationId) {
    return getOrganization
        .handle(new GetOrganizationForPlatformAccountQuery(organizationId, owner))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private UUID requireCurrentPlatformAccount(final HttpServletRequest request) {
    return currentPlatformAccount
        .resolve(request)
        .orElseThrow(
            () -> new IllegalStateException("No authenticated PlatformAccount on this request"));
  }
}
