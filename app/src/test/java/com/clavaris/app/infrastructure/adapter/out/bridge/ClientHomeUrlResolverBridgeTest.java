package com.clavaris.app.infrastructure.adapter.out.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The "back to home" link on the sign-up and forgot-password confirmation pages: proves the home is
 * only ever the origin of a registered redirect URI, and that an unknown or cross-tenant client
 * gets nothing.
 */
class ClientHomeUrlResolverBridgeTest {

  private final OAuthClientRepository oauthClients = mock(OAuthClientRepository.class);
  private final ClientHomeUrlResolverBridge bridge = new ClientHomeUrlResolverBridge(oauthClients);

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

  @Test
  void hasNoHomeWithoutAClientId() {
    assertThat(bridge.resolve(organizationId, null)).isEmpty();
  }

  @Test
  void hasNoHomeForAnUnknownClient() {
    when(oauthClients.findByClientId("unknown")).thenReturn(Optional.empty());

    assertThat(bridge.resolve(organizationId, "unknown")).isEmpty();
  }

  @Test
  void hasNoHomeForAClientOfAnotherOrganization() {
    when(oauthClients.findByClientId("other"))
        .thenReturn(
            Optional.of(aClient(UUID.randomUUID(), List.of("https://app.example.com/callback"))));

    assertThat(bridge.resolve(organizationId, "other")).isEmpty();
  }

  @Test
  void theHomeIsTheOriginOfTheRegisteredRedirectUri() {
    when(oauthClients.findByClientId("web"))
        .thenReturn(
            Optional.of(
                aClient(
                    organizationId.value(),
                    List.of("https://app.example.com/auth/callback?x=1#frag"))));

    assertThat(bridge.resolve(organizationId, "web")).contains("https://app.example.com/");
  }

  @Test
  void aNonDefaultPortIsKept() {
    when(oauthClients.findByClientId("dev"))
        .thenReturn(
            Optional.of(aClient(organizationId.value(), List.of("http://localhost:3000/cb"))));

    assertThat(bridge.resolve(organizationId, "dev")).contains("http://localhost:3000/");
  }

  @Test
  void withSeveralRedirectUrisTheAlphabeticallyFirstWebOriginIsUsed() {
    when(oauthClients.findByClientId("multi"))
        .thenReturn(
            Optional.of(
                aClient(
                    organizationId.value(),
                    List.of("https://zeta.example.com/cb", "https://alpha.example.com/cb"))));

    assertThat(bridge.resolve(organizationId, "multi")).contains("https://alpha.example.com/");
  }

  @Test
  void aNativeAppWithOnlyACustomSchemeHasNoHome() {
    when(oauthClients.findByClientId("native"))
        .thenReturn(Optional.of(aClient(organizationId.value(), List.of("com.example.app:/cb"))));

    assertThat(bridge.resolve(organizationId, "native")).isEmpty();
  }

  @Test
  void aWebOriginIsPreferredOverACustomSchemeRegisteredBesideIt() {
    when(oauthClients.findByClientId("both"))
        .thenReturn(
            Optional.of(
                aClient(
                    organizationId.value(),
                    List.of("com.example.app:/cb", "https://app.example.com/cb"))));

    assertThat(bridge.resolve(organizationId, "both")).contains("https://app.example.com/");
  }

  private static OAuthClient aClient(final UUID organization, final List<String> redirectUris) {
    return OAuthClient.reconstitute(
        UUID.randomUUID(),
        organization,
        "a-client",
        "argon2id$hashed",
        redirectUris,
        List.of("authorization_code"),
        List.of("openid"),
        true,
        List.of(),
        Instant.now(),
        true,
        0);
  }
}
