package com.clavaris.app.infrastructure.adapter.out.bridge;

import com.clavaris.clientregistry.application.usecase.getclientbranding.GetClientBrandingUseCase;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.ClientBranding;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.identity.application.usecase.resolveclientbranding.ClientBrandingProvider;
import com.clavaris.identity.application.usecase.resolveclientbranding.ClientBrandingSnapshot;
import com.clavaris.identity.application.usecase.resolveorganizationname.OrganizationNameProvider;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.organization.application.usecase.getorganizationprofiles.GetOrganizationProfilesUseCase;
import com.clavaris.organization.domain.model.OrganizationProfile;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Adapts client-registry-module's {@code OAuthClientRepository}/{@code GetClientBrandingUseCase} to
 * identity-module's own {@link ClientBrandingProvider} port — same module-independence-crossing
 * bridge pattern {@link RedirectUrlResolverBridge} already establishes for an identical need.
 */
@Component
class ClientBrandingProviderBridge implements ClientBrandingProvider {

  private final OAuthClientRepository oauthClients;
  private final GetClientBrandingUseCase getClientBranding;
  private final OrganizationNameProvider organizationNames;
  private final GetOrganizationProfilesUseCase getProfiles;

  /* package */ ClientBrandingProviderBridge(
      final OAuthClientRepository oauthClients,
      final GetClientBrandingUseCase getClientBranding,
      final OrganizationNameProvider organizationNames,
      final GetOrganizationProfilesUseCase getProfiles) {
    this.oauthClients = oauthClients;
    this.getClientBranding = getClientBranding;
    this.organizationNames = organizationNames;
    this.getProfiles = getProfiles;
  }

  // Name, logo and colour each resolve in the same order: the client's own branding, then what its
  // Organization set on its profile and, for the name only, finally the Organization's name. The
  // pages these snapshots feed (sign-in, consent) are the consuming application's, so they always
  // have a name of its own to show and never fall back to Clavaris.
  @Override
  public ClientBrandingSnapshot brandingFor(
      final OrganizationId organizationId, final String clientId) {
    final ClientBrandingSnapshot client = configuredBranding(organizationId, clientId);
    final UUID organization = organizationId.value();
    final Optional<OrganizationProfile> profile =
        Optional.ofNullable(getProfiles.handle(List.of(organization)).get(organization));
    return new ClientBrandingSnapshot(
        client.logoUrl().or(() -> profile.flatMap(ClientBrandingProviderBridge::logoUrlOf)),
        client.primaryColor().or(() -> profile.flatMap(OrganizationProfile::brandColor)),
        client.applicationDisplayName().or(() -> organizationNames.nameFor(organizationId)));
  }

  // The Organization's uploaded logo, served by organization-module's OrganizationLogoController.
  // The path is relative, so the sign-in page's img-src 'self' already allows it; ?v= is when the
  // logo was last set, so a replaced logo has a new URL and never shows a stale cached copy.
  private static Optional<String> logoUrlOf(final OrganizationProfile profile) {
    return profile
        .logoUpdatedAt()
        .map(Instant::toEpochMilli)
        .map(version -> "/o/" + profile.organizationId() + "/branding/logo?v=" + version);
  }

  // Two exits (no usable client context / a resolved snapshot) — same rationale
  // RedirectUrlResolverBridge's own identical suppression documents.
  @SuppressWarnings("PMD.OnlyOneReturn")
  private ClientBrandingSnapshot configuredBranding(
      final OrganizationId organizationId, final String clientId) {
    if (clientId == null) {
      return ClientBrandingSnapshot.unconfigured();
    }
    final Optional<OAuthClient> maybeClient = oauthClients.findByClientId(clientId);
    // BR-ORG-02-style cross-tenant defence in depth — same convention RedirectUrlResolverBridge's
    // own identical check documents.
    if (maybeClient.isEmpty()
        || !maybeClient.get().organizationId().equals(organizationId.value())) {
      return ClientBrandingSnapshot.unconfigured();
    }
    final ClientBranding branding =
        getClientBranding.handle(organizationId.value(), maybeClient.get().id());
    return new ClientBrandingSnapshot(
        branding.logoUrl(), branding.primaryColor(), branding.applicationDisplayName());
  }
}
