package com.clavaris.clientregistry.application.usecase.getredirectpolicyforclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.setredirectpolicyforclient.RedirectPolicyRepository;
import com.clavaris.clientregistry.domain.model.RedirectPolicy;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GetRedirectPolicyForClientServiceTest {

  @Test
  void returnsTheConfiguredPolicyWhenOneExists() {
    UUID oauthClientId = UUID.randomUUID();
    RedirectPolicy existing =
        RedirectPolicy.define(oauthClientId, "https://app.example.com/a", null, null, null);
    RedirectPolicyRepository policies = mock(RedirectPolicyRepository.class);
    when(policies.findByOAuthClientId(oauthClientId)).thenReturn(Optional.of(existing));

    RedirectPolicy result = new GetRedirectPolicyForClientService(policies).handle(oauthClientId);

    assertThat(result).isEqualTo(existing);
  }

  @Test
  void returnsUnconfiguredDefaultsWhenNoPolicyHasEverBeenSet() {
    UUID oauthClientId = UUID.randomUUID();
    RedirectPolicyRepository policies = mock(RedirectPolicyRepository.class);
    when(policies.findByOAuthClientId(oauthClientId)).thenReturn(Optional.empty());

    RedirectPolicy result = new GetRedirectPolicyForClientService(policies).handle(oauthClientId);

    assertThat(result.oauthClientId()).isEqualTo(oauthClientId);
    assertThat(result.fallbackSignInRedirectUrl()).isEmpty();
    assertThat(result.forceSignInRedirectUrl()).isEmpty();
  }
}
