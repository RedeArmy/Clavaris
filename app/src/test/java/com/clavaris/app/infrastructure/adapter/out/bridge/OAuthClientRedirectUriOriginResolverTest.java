package com.clavaris.app.infrastructure.adapter.out.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Security finding, 2026-10-09: see RedirectUriOriginResolver's own Javadoc for the real-world
// browser behavior this class exists to work around.
class OAuthClientRedirectUriOriginResolverTest {

  private final OAuthClientRepository oauthClients = mock(OAuthClientRepository.class);
  private final OAuthClientRedirectUriOriginResolver resolver =
      new OAuthClientRedirectUriOriginResolver(oauthClients);

  @Test
  void resolvesEmptyForANullClientId() {
    assertThat(resolver.resolveAllowedFormActionOrigins(null, UUID.randomUUID())).isEmpty();
  }

  @Test
  void resolvesEmptyForAnUnknownClientId() {
    when(oauthClients.findByClientId("unknown-client")).thenReturn(Optional.empty());

    assertThat(resolver.resolveAllowedFormActionOrigins("unknown-client", UUID.randomUUID()))
        .isEmpty();
  }

  @Test
  void resolvesTheOriginOfItsSingleRegisteredRedirectUri() {
    OAuthClient client = anOAuthClient(List.of("https://app.example.com/callback"));
    when(oauthClients.findByClientId("a-client")).thenReturn(Optional.of(client));

    assertThat(resolver.resolveAllowedFormActionOrigins("a-client", client.organizationId()))
        .containsExactly("https://app.example.com");
  }

  // The port is part of the origin — https://content-security-policy.com/form-action's own
  // grammar (and 'self' itself) treats a different port as a genuinely different origin.
  @Test
  void keepsAnExplicitNonDefaultPortAsPartOfTheOrigin() {
    OAuthClient client = anOAuthClient(List.of("http://localhost:5080/signin-oidc"));
    when(oauthClients.findByClientId("a-client")).thenReturn(Optional.of(client));

    assertThat(resolver.resolveAllowedFormActionOrigins("a-client", client.organizationId()))
        .containsExactly("http://localhost:5080");
  }

  @Test
  void deduplicatesWhenSeveralRedirectUrisShareTheSameOrigin() {
    OAuthClient client =
        anOAuthClient(
            List.of(
                "https://app.example.com/callback-one", "https://app.example.com/callback-two"));
    when(oauthClients.findByClientId("a-client")).thenReturn(Optional.of(client));

    assertThat(resolver.resolveAllowedFormActionOrigins("a-client", client.organizationId()))
        .containsExactly("https://app.example.com");
  }

  @Test
  void resolvesEveryDistinctOriginAcrossMultipleRedirectUris() {
    OAuthClient client =
        anOAuthClient(List.of("https://a.example.com/callback", "https://b.example.com/callback"));
    when(oauthClients.findByClientId("a-client")).thenReturn(Optional.of(client));

    assertThat(resolver.resolveAllowedFormActionOrigins("a-client", client.organizationId()))
        .containsExactlyInAnyOrder("https://a.example.com", "https://b.example.com");
  }

  // Security finding, 2026-10-09: same cross-tenant posture OAuthClientEmbeddingEligibilityChecker
  // already establishes for frame-ancestors — a resolved OAuthClient belonging to a different
  // Organization than this request's own path actually names must never be trusted, or an
  // attacker-controlled clientId on one Organization's own login page could widen form-action to
  // some unrelated Organization's registered client.
  @Test
  void resolvesEmptyWhenTheClientBelongsToADifferentOrganizationThanExpected() {
    OAuthClient client = anOAuthClient(List.of("https://app.example.com/callback"));
    when(oauthClients.findByClientId("a-client")).thenReturn(Optional.of(client));
    UUID aDifferentOrganizationsId = UUID.randomUUID();

    assertThat(resolver.resolveAllowedFormActionOrigins("a-client", aDifferentOrganizationsId))
        .isEmpty();
  }

  // The consent page's own flat, org-agnostic path has no organizationId to cross-check against —
  // same null opt-out EmbeddingEligibilityChecker's own identical parameter documents.
  @Test
  void resolvesTheOriginRegardlessOfOrganizationWhenNoneIsExpected() {
    OAuthClient client = anOAuthClient(List.of("https://app.example.com/callback"));
    when(oauthClients.findByClientId("a-client")).thenReturn(Optional.of(client));

    assertThat(resolver.resolveAllowedFormActionOrigins("a-client", null))
        .containsExactly("https://app.example.com");
  }

  private static OAuthClient anOAuthClient(final List<String> redirectUris) {
    return OAuthClient.register(
        UUID.randomUUID(),
        "a-client",
        "argon2id$hashed",
        redirectUris,
        List.of("authorization_code"),
        List.of("openid"),
        true,
        List.of());
  }
}
