package com.clavaris.clientregistry.application.usecase.getclientbranding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.setclientbranding.ClientBrandingRepository;
import com.clavaris.clientregistry.domain.model.ClientBranding;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GetClientBrandingServiceTest {

  @Test
  void returnsTheConfiguredBrandingWhenOneExists() {
    UUID oauthClientId = UUID.randomUUID();
    ClientBranding existing =
        ClientBranding.define(oauthClientId, "https://cdn.example.com/logo.png", null, null);
    ClientBrandingRepository brandings = mock(ClientBrandingRepository.class);
    when(brandings.findByOAuthClientId(oauthClientId)).thenReturn(Optional.of(existing));

    ClientBranding result = new GetClientBrandingService(brandings).handle(oauthClientId);

    assertThat(result).isEqualTo(existing);
  }

  @Test
  void returnsUnconfiguredDefaultsWhenNoBrandingHasEverBeenSet() {
    UUID oauthClientId = UUID.randomUUID();
    ClientBrandingRepository brandings = mock(ClientBrandingRepository.class);
    when(brandings.findByOAuthClientId(oauthClientId)).thenReturn(Optional.empty());

    ClientBranding result = new GetClientBrandingService(brandings).handle(oauthClientId);

    assertThat(result.oauthClientId()).isEqualTo(oauthClientId);
    assertThat(result.logoUrl()).isEmpty();
    assertThat(result.primaryColor()).isEmpty();
    assertThat(result.applicationDisplayName()).isEmpty();
  }
}
