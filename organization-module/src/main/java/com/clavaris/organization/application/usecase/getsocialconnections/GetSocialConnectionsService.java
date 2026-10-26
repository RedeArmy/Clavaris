package com.clavaris.organization.application.usecase.getsocialconnections;

import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.savesocialconnection.SocialConnectionSettingsRepository;
import com.clavaris.organization.application.usecase.setorganizationsocialcredential.OrganizationSocialCredentialRepository;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.OrganizationEnvironment;
import com.clavaris.organization.domain.model.OrganizationSocialCredential;
import com.clavaris.organization.domain.model.SocialConnectionSettings;
import com.clavaris.organization.domain.model.SocialProvider;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestration for {@link GetSocialConnectionsUseCase}. The status is worked out here, once, so
 * the page and the sign-in pages agree on what "used for sign-in" means: a provider is offered when
 * it is switched on and, for a PRODUCTION Organization, has credentials of its own.
 */
public class GetSocialConnectionsService implements GetSocialConnectionsUseCase {

  private final OrganizationRepository organizations;
  private final OrganizationSocialCredentialRepository credentials;
  private final SocialConnectionSettingsRepository settings;
  private final String clavarisBaseUrl;

  public GetSocialConnectionsService(
      final OrganizationRepository organizations,
      final OrganizationSocialCredentialRepository credentials,
      final SocialConnectionSettingsRepository settings,
      final String clavarisBaseUrl) {
    this.organizations = organizations;
    this.credentials = credentials;
    this.settings = settings;
    this.clavarisBaseUrl = clavarisBaseUrl;
  }

  @Override
  public SocialConnections handle(final UUID organizationId) {
    final Organization organization =
        organizations
            .findById(organizationId)
            .orElseThrow(() -> new OrganizationNotFoundException(organizationId));
    final List<OrganizationSocialCredential> ownCredentials =
        credentials.findAllByOrganizationId(organizationId);
    final List<SocialConnectionSettings> savedSettings =
        settings.findAllByOrganizationId(organizationId);
    return new SocialConnections(
        organization.environment(),
        Arrays.stream(SocialProvider.values())
            .map(
                provider ->
                    connection(
                        organization,
                        provider,
                        ownCredentials.stream().filter(c -> c.provider() == provider).findFirst(),
                        savedSettings.stream()
                            .filter(s -> s.provider() == provider)
                            .findFirst()
                            .orElseGet(
                                () -> SocialConnectionSettings.unset(organizationId, provider))))
            .toList());
  }

  private SocialConnection connection(
      final Organization organization,
      final SocialProvider provider,
      final Optional<OrganizationSocialCredential> credential,
      final SocialConnectionSettings connectionSettings) {
    final boolean enabled =
        organization.socialLoginEnabled()
            && organization.allowedSocialProviders().contains(provider.name());
    final boolean production = organization.environment() == OrganizationEnvironment.PRODUCTION;
    return new SocialConnection(
        provider,
        statusOf(enabled, production, credential.isPresent()),
        enabled,
        credential.isPresent(),
        credential.map(OrganizationSocialCredential::clientId),
        connectionSettings.blockEmailSubaddresses(),
        connectionSettings.alwaysShowAccountSelector(),
        clavarisBaseUrl + "/login/oauth2/code/" + provider.name().toLowerCase(Locale.ROOT));
  }

  private static SocialConnectionStatus statusOf(
      final boolean enabled, final boolean production, final boolean hasCredentials) {
    final SocialConnectionStatus status;
    if (enabled) {
      status =
          production && !hasCredentials
              ? SocialConnectionStatus.SETUP_REQUIRED
              : SocialConnectionStatus.ENABLED;
    } else {
      status = SocialConnectionStatus.DISABLED;
    }
    return status;
  }
}
