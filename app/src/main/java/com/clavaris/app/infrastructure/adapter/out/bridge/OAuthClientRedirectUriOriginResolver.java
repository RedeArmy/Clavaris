package com.clavaris.app.infrastructure.adapter.out.bridge;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * See {@link RedirectUriOriginResolver}'s own Javadoc for the full rationale. Same {@code
 * OAuthClientRepository}, same cross-tenant-check shape {@code
 * OAuthClientEmbeddingEligibilityChecker} already establishes for an identical need — a resolved
 * {@code OAuthClient} belonging to a different Organization than expected is treated identically to
 * "unknown client".
 */
// PMD.OnlyOneReturn: "unknown client" / "resolved" are two independent, equally valid exits — same
// rationale OAuthClientEmbeddingEligibilityChecker's own identical suppression documents.
// PMD.LongVariable: see RedirectUriOriginResolver's own identical suppression.
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.LongVariable"})
@Component
class OAuthClientRedirectUriOriginResolver implements RedirectUriOriginResolver {

  private final OAuthClientRepository oauthClients;

  /* package */ OAuthClientRedirectUriOriginResolver(final OAuthClientRepository oauthClients) {
    this.oauthClients = oauthClients;
  }

  @Override
  public List<String> resolveAllowedFormActionOrigins(
      final String clientId, final UUID expectedOrganizationId) {
    if (clientId == null) {
      return List.of();
    }
    return oauthClients
        .findByClientId(clientId)
        .filter(client -> belongsToExpectedOrganization(client, expectedOrganizationId))
        .map(OAuthClientRedirectUriOriginResolver::originsOf)
        .orElseGet(List::of);
  }

  // Security finding, 2026-10-09: same posture OAuthClientEmbeddingEligibilityChecker's own
  // identical check already establishes — never trust a resolved OAuthClient just because its
  // clientId matched, if it belongs to a different Organization than the one this request's own
  // path actually names.
  private static boolean belongsToExpectedOrganization(
      final OAuthClient client, final UUID expectedOrganizationId) {
    return expectedOrganizationId == null || client.organizationId().equals(expectedOrganizationId);
  }

  // Deduplicated, scheme+host+port only — never the path/query a redirectUri also carries, which
  // form-action's own grammar neither expects nor needs (an origin is enough to permit the whole
  // host). A malformed stored URI is skipped, not thrown — every redirectUri was already validated
  // at registration time (OAuthClient's own requireValidUris), so this is defense-in-depth, not the
  // normal path.
  private static List<String> originsOf(final OAuthClient client) {
    return client.redirectUris().stream()
        .map(OAuthClientRedirectUriOriginResolver::originOf)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();
  }

  private static String originOf(final String redirectUri) {
    try {
      final URI uri = new URI(redirectUri);
      if (uri.getScheme() == null || uri.getAuthority() == null) {
        return null;
      }
      return uri.getScheme() + "://" + uri.getAuthority();
    } catch (final URISyntaxException _) {
      return null;
    }
  }
}
