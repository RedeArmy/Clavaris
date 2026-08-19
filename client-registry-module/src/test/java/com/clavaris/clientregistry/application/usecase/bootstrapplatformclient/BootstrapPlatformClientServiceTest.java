package com.clavaris.clientregistry.application.usecase.bootstrapplatformclient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BootstrapPlatformClientServiceTest {

  private PlatformClientRepository platformClients;
  private ClientSecretHasher hasher;
  private BootstrapPlatformClientService service;

  @BeforeEach
  void setUp() {
    platformClients = mock(PlatformClientRepository.class);
    hasher = mock(ClientSecretHasher.class);
    service = new BootstrapPlatformClientService(platformClients, hasher);

    when(hasher.hash(anyString())).thenReturn("hashed-secret");
  }

  @Test
  void seedsANewPlatformClientWhenNoneExistsYet() {
    when(platformClients.existsByClientId("bootstrap-client")).thenReturn(false);

    service.handle(new BootstrapPlatformClientCommand("bootstrap-client", "a-raw-secret"));

    verify(platformClients).save(any());
    verify(hasher).hash("a-raw-secret");
  }

  @Test
  void isIdempotent_doesNothingIfAlreadySeeded() {
    // BR-PLATFORM-03: a redeploy/restart must never fail, or silently create a second row / spin
    // a new secret out from under whatever already trusts the existing one.
    when(platformClients.existsByClientId("bootstrap-client")).thenReturn(true);

    service.handle(new BootstrapPlatformClientCommand("bootstrap-client", "a-raw-secret"));

    verify(platformClients, never()).save(any());
  }
}
