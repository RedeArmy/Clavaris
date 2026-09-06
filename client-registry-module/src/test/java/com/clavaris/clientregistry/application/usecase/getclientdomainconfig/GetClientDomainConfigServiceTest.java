package com.clavaris.clientregistry.application.usecase.getclientdomainconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.ClientDomainConfigRepository;
import com.clavaris.clientregistry.domain.model.ClientDomainConfig;
import com.clavaris.clientregistry.domain.model.ClientDomainMode;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GetClientDomainConfigServiceTest {

  @Test
  void returnsTheConfiguredDomainWhenOneExists() {
    UUID oauthClientId = UUID.randomUUID();
    ClientDomainConfig existing =
        ClientDomainConfig.request(
            oauthClientId, ClientDomainMode.CNAME, "login.example.com", null);
    ClientDomainConfigRepository domainConfigs = mock(ClientDomainConfigRepository.class);
    when(domainConfigs.findByOAuthClientId(oauthClientId)).thenReturn(Optional.of(existing));

    ClientDomainConfig result =
        new GetClientDomainConfigService(domainConfigs).handle(oauthClientId);

    assertThat(result).isEqualTo(existing);
  }

  @Test
  void returnsUnconfiguredSharedModeDefaultsWhenNoDomainHasEverBeenRequested() {
    UUID oauthClientId = UUID.randomUUID();
    ClientDomainConfigRepository domainConfigs = mock(ClientDomainConfigRepository.class);
    when(domainConfigs.findByOAuthClientId(oauthClientId)).thenReturn(Optional.empty());

    ClientDomainConfig result =
        new GetClientDomainConfigService(domainConfigs).handle(oauthClientId);

    assertThat(result.oauthClientId()).isEqualTo(oauthClientId);
    assertThat(result.mode()).isEmpty();
    assertThat(result.hostname()).isEmpty();
  }
}
