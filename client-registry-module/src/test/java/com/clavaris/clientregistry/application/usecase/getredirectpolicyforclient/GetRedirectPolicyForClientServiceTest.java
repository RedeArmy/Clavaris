package com.clavaris.clientregistry.application.usecase.getredirectpolicyforclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.application.usecase.setredirectpolicyforclient.RedirectPolicyRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.clientregistry.domain.model.RedirectPolicy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GetRedirectPolicyForClientServiceTest {

  private OAuthClientRepository oauthClients;
  private RedirectPolicyRepository policies;
  private GetRedirectPolicyForClientService service;

  @BeforeEach
  void setUp() {
    oauthClients = mock(OAuthClientRepository.class);
    policies = mock(RedirectPolicyRepository.class);
    service = new GetRedirectPolicyForClientService(oauthClients, policies);
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
  void returnsTheConfiguredPolicyWhenOneExists() {
    UUID organizationId = UUID.randomUUID();
    OAuthClient client = registeredClient(organizationId);
    RedirectPolicy existing =
        RedirectPolicy.define(client.id(), "https://app.example.com/a", null, null, null);
    when(oauthClients.findById(client.id())).thenReturn(Optional.of(client));
    when(policies.findByOAuthClientId(client.id())).thenReturn(Optional.of(existing));

    RedirectPolicy result = service.handle(organizationId, client.id());

    assertThat(result).isEqualTo(existing);
  }

  @Test
  void returnsUnconfiguredDefaultsWhenNoPolicyHasEverBeenSet() {
    UUID organizationId = UUID.randomUUID();
    OAuthClient client = registeredClient(organizationId);
    when(oauthClients.findById(client.id())).thenReturn(Optional.of(client));
    when(policies.findByOAuthClientId(client.id())).thenReturn(Optional.empty());

    RedirectPolicy result = service.handle(organizationId, client.id());

    assertThat(result.oauthClientId()).isEqualTo(client.id());
    assertThat(result.fallbackSignInRedirectUrl()).isEmpty();
    assertThat(result.forceSignInRedirectUrl()).isEmpty();
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
  // different Organization's own oauthClientId would read back that Organization's real
  // fallback/force redirect URLs.
  @Test
  void rejectsAClientThatBelongsToADifferentOrganization() {
    OAuthClient client = registeredClient(UUID.randomUUID());
    UUID unrelatedOrganizationId = UUID.randomUUID();
    when(oauthClients.findById(client.id())).thenReturn(Optional.of(client));

    assertThatExceptionOfType(OAuthClientNotFoundException.class)
        .isThrownBy(() -> service.handle(unrelatedOrganizationId, client.id()));
  }
}
