package com.clavaris.app.infrastructure.adapter.out.bridge;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.identity.application.usecase.resolveclienthomeurl.ClientHomeUrlResolver;
import com.clavaris.identity.domain.model.OrganizationId;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Adapts client-registry-module's {@code OAuthClientRepository} to identity-module's own {@link
 * ClientHomeUrlResolver} port. A client's home is the origin ({@code scheme://host[:port]/}) of its
 * first registered {@code redirect_uri}, in alphabetical order so the answer is stable. That URI is
 * already vetted when the client is registered, so nothing the request carries ever reaches the
 * link. A client whose redirect URIs are not web addresses (a native app's custom scheme) has no
 * home to link to.
 */
@Component
class ClientHomeUrlResolverBridge implements ClientHomeUrlResolver {

  private final OAuthClientRepository oauthClients;

  /* package */ ClientHomeUrlResolverBridge(final OAuthClientRepository oauthClients) {
    this.oauthClients = oauthClients;
  }

  @Override
  public Optional<String> resolve(final OrganizationId organizationId, final String clientId) {
    return Optional.ofNullable(clientId)
        .flatMap(oauthClients::findByClientId)
        // An unknown client and one that belongs to another Organization are the same answer.
        .filter(client -> client.organizationId().equals(organizationId.value()))
        .map(OAuthClient::redirectUris)
        .flatMap(
            uris ->
                uris.stream()
                    .sorted()
                    .map(ClientHomeUrlResolverBridge::originOf)
                    .flatMap(Optional::stream)
                    .findFirst());
  }

  // "https://app.example.com:8443/auth/callback?x=1" -> "https://app.example.com:8443/".
  // Three genuinely distinct outcomes (malformed, not a web address, an origin) — same rationale as
  // every other early-return chain in the bridges.
  @SuppressWarnings("PMD.OnlyOneReturn")
  private static Optional<String> originOf(final String redirectUri) {
    try {
      final URI uri = new URI(redirectUri);
      final String scheme = uri.getScheme();
      final boolean web = "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
      if (!web || uri.getHost() == null) {
        return Optional.empty();
      }
      final String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
      return Optional.of(scheme.toLowerCase(Locale.ROOT) + "://" + uri.getHost() + port + "/");
    } catch (final URISyntaxException e) {
      return Optional.empty();
    }
  }
}
