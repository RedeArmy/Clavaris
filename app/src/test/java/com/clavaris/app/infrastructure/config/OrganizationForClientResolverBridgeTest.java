package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * TD-SEC-011: proves the bridge's own {@code client_id}-to-{@code OrganizationId} resolution — the
 * one direction {@link ConsentController} needs and no other controller in this codebase does (see
 * {@link OrganizationForClientResolver}'s own Javadoc for why).
 */
class OrganizationForClientResolverBridgeTest {

  private final OAuthClientRepository oauthClients = mock(OAuthClientRepository.class);
  private final OrganizationForClientResolverBridge bridge =
      new OrganizationForClientResolverBridge(oauthClients);

  @Test
  void resolvesTheOwningOrganizationForAKnownClient() {
    UUID organizationId = UUID.randomUUID();
    OAuthClient client = anOAuthClient(organizationId);
    when(oauthClients.findByClientId("jobseeker-web")).thenReturn(Optional.of(client));

    Optional<OrganizationId> resolved = bridge.resolve("jobseeker-web");

    assertThat(resolved).contains(new OrganizationId(organizationId));
  }

  @Test
  void resolvesEmptyForAnUnknownClientId() {
    when(oauthClients.findByClientId("unknown-client")).thenReturn(Optional.empty());

    Optional<OrganizationId> resolved = bridge.resolve("unknown-client");

    assertThat(resolved).isEmpty();
  }

  private static OAuthClient anOAuthClient(final UUID organizationId) {
    return OAuthClient.register(
        organizationId,
        "jobseeker-web",
        "argon2id$hashed",
        List.of("https://app.example.com/callback"),
        List.of("authorization_code"),
        List.of("openid"),
        true,
        List.of());
  }
}
