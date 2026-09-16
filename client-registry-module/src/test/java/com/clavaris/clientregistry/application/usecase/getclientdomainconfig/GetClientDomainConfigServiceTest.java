package com.clavaris.clientregistry.application.usecase.getclientdomainconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.ClientDomainConfigRepository;
import com.clavaris.clientregistry.domain.model.ClientDomainConfig;
import com.clavaris.clientregistry.domain.model.ClientDomainMode;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GetClientDomainConfigServiceTest {

  private OAuthClientRepository oauthClients;
  private ClientDomainConfigRepository domainConfigs;
  private GetClientDomainConfigService service;

  @BeforeEach
  void setUp() {
    oauthClients = mock(OAuthClientRepository.class);
    domainConfigs = mock(ClientDomainConfigRepository.class);
    service = new GetClientDomainConfigService(oauthClients, domainConfigs);
  }

  private OAuthClient registeredClient(final UUID organizationId) {
    return OAuthClient.register(
        organizationId,
        "test_client",
        "hashed-secret",
        List.of("https://app.example.com/callback"),
        List.of("authorization_code"),
        List.of("openid"),
        true,
        List.of());
  }

  @Test
  void returnsTheConfiguredDomainWhenOneExists() {
    UUID organizationId = UUID.randomUUID();
    OAuthClient client = registeredClient(organizationId);
    ClientDomainConfig existing =
        ClientDomainConfig.request(client.id(), ClientDomainMode.CNAME, "login.example.com", null);
    when(oauthClients.findById(client.id())).thenReturn(Optional.of(client));
    when(domainConfigs.findByOAuthClientId(client.id())).thenReturn(Optional.of(existing));

    ClientDomainConfig result = service.handle(organizationId, client.id());

    assertThat(result).isEqualTo(existing);
  }

  @Test
  void returnsUnconfiguredSharedModeDefaultsWhenNoDomainHasEverBeenRequested() {
    UUID organizationId = UUID.randomUUID();
    OAuthClient client = registeredClient(organizationId);
    when(oauthClients.findById(client.id())).thenReturn(Optional.of(client));
    when(domainConfigs.findByOAuthClientId(client.id())).thenReturn(Optional.empty());

    ClientDomainConfig result = service.handle(organizationId, client.id());

    assertThat(result.oauthClientId()).isEqualTo(client.id());
    assertThat(result.mode()).isEmpty();
    assertThat(result.hostname()).isEmpty();
  }

  @Test
  void rejectsANonExistentOAuthClient() {
    UUID organizationId = UUID.randomUUID();
    UUID nonExistentClientId = UUID.randomUUID();
    when(oauthClients.findById(nonExistentClientId)).thenReturn(Optional.empty());

    assertThatExceptionOfType(OAuthClientNotFoundException.class)
        .isThrownBy(() -> service.handle(organizationId, nonExistentClientId));
  }

  // SDE-III review, 2026-09-15 — the real regression this guards: before this fix, this method
  // never even accepted an organizationId, so a caller pairing a valid organizationId with a
  // different Organization's own oauthClientId would read back that Organization's real custom
  // domain hostname/verification status.
  @Test
  void rejectsAClientThatBelongsToADifferentOrganization() {
    OAuthClient client = registeredClient(UUID.randomUUID());
    UUID unrelatedOrganizationId = UUID.randomUUID();
    UUID clientId = client.id();
    when(oauthClients.findById(clientId)).thenReturn(Optional.of(client));

    // java:S5778 (SDE-III review, 2026-09-16): clientId resolved above, outside the lambda, so
    // exactly one invocation that could throw remains inside the assertion — the one actually under
    // test.
    assertThatExceptionOfType(OAuthClientNotFoundException.class)
        .isThrownBy(() -> service.handle(unrelatedOrganizationId, clientId));
  }
}
