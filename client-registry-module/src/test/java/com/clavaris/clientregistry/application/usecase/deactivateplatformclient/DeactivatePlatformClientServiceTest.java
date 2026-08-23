package com.clavaris.clientregistry.application.usecase.deactivateplatformclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.PlatformClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.PlatformClientRepository;
import com.clavaris.clientregistry.domain.model.PlatformClient;
import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeactivatePlatformClientServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("operator-client");

  private PlatformClientRepository platformClients;
  private AuditEventRecorder auditEvents;
  private DeactivatePlatformClientService service;

  @BeforeEach
  void setUp() {
    platformClients = mock(PlatformClientRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    service = new DeactivatePlatformClientService(platformClients, auditEvents);
  }

  @Test
  void savesTheClientWithActiveFalse() {
    PlatformClient existing = PlatformClient.register("target-client", "$argon2id$hash", List.of());
    assertThat(existing.active()).isTrue();
    when(platformClients.findByClientId("target-client")).thenReturn(Optional.of(existing));

    service.handle(new DeactivatePlatformClientCommand("target-client", ACTOR));

    verify(platformClients).save(argThat(saved -> !saved.active()));
  }

  @Test
  void recordsAnAuditEvent() {
    PlatformClient existing = PlatformClient.register("target-client", "$argon2id$hash", List.of());
    when(platformClients.findByClientId("target-client")).thenReturn(Optional.of(existing));

    service.handle(new DeactivatePlatformClientCommand("target-client", ACTOR));

    verify(auditEvents)
        .write(
            eq(ACTOR),
            eq("platform_client.deactivated"),
            eq("PlatformClient"),
            eq("target-client"),
            eq(null));
  }

  @Test
  void rejectsAnUnknownClientIdWithoutPersistingOrRecordingAnything() {
    when(platformClients.findByClientId("ghost-client")).thenReturn(Optional.empty());

    assertThatExceptionOfType(PlatformClientNotFoundException.class)
        .isThrownBy(
            () -> service.handle(new DeactivatePlatformClientCommand("ghost-client", ACTOR)));

    verify(platformClients, never()).save(any());
    verifyNoInteractions(auditEvents);
  }
}
