package com.clavaris.clientregistry.application.usecase.rotateplatformclientsecret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.ClientSecretHasher;
import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.PlatformClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.PlatformClientRepository;
import com.clavaris.clientregistry.domain.model.PlatformClient;
import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RotatePlatformClientSecretServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("operator-client");

  private PlatformClientRepository platformClients;
  private ClientSecretHasher hasher;
  private PlatformClientSecretGenerator secretGenerator;
  private AuditEventRecorder auditEvents;
  private RotatePlatformClientSecretService service;

  @BeforeEach
  void setUp() {
    platformClients = mock(PlatformClientRepository.class);
    hasher = mock(ClientSecretHasher.class);
    secretGenerator = mock(PlatformClientSecretGenerator.class);
    auditEvents = mock(AuditEventRecorder.class);
    service =
        new RotatePlatformClientSecretService(
            platformClients, hasher, secretGenerator, auditEvents);
  }

  @Test
  void generatesAFreshSecretHashesItAndPersistsTheRotatedClient() {
    PlatformClient existing =
        PlatformClient.register("target-client", "$argon2id$old-hash", List.of());
    when(platformClients.findByClientId("target-client")).thenReturn(Optional.of(existing));
    when(secretGenerator.generate()).thenReturn("a-fresh-raw-secret");
    when(hasher.hash("a-fresh-raw-secret")).thenReturn("$argon2id$new-hash");

    RotatePlatformClientSecretResult result =
        service.handle(new RotatePlatformClientSecretCommand("target-client", ACTOR));

    assertThat(result.rawSecret())
        .as("the caller must get back the raw secret exactly once")
        .isEqualTo("a-fresh-raw-secret");
    verify(platformClients)
        .save(
            org.mockito.ArgumentMatchers.argThat(
                saved -> saved.clientSecretHash().equals("$argon2id$new-hash")));
  }

  // TD-SEC-007: rotation is exactly the action the technical-debt register named as needing to
  // be audited.
  @Test
  void recordsAnAuditEventNamingNeitherTheRawSecretNorItsHash() {
    PlatformClient existing =
        PlatformClient.register("target-client", "$argon2id$old-hash", List.of());
    when(platformClients.findByClientId("target-client")).thenReturn(Optional.of(existing));
    when(secretGenerator.generate()).thenReturn("a-fresh-raw-secret");
    when(hasher.hash("a-fresh-raw-secret")).thenReturn("$argon2id$new-hash");

    service.handle(new RotatePlatformClientSecretCommand("target-client", ACTOR));

    verify(auditEvents)
        .write(ACTOR, "platform_client.secret_rotated", "PlatformClient", "target-client", null);
  }

  @Test
  void rejectsAnUnknownClientIdWithoutGeneratingOrPersistingAnything() {
    when(platformClients.findByClientId("ghost-client")).thenReturn(Optional.empty());
    RotatePlatformClientSecretCommand command =
        new RotatePlatformClientSecretCommand("ghost-client", ACTOR);

    assertThatExceptionOfType(PlatformClientNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verifyNoInteractions(secretGenerator);
    verifyNoInteractions(hasher);
    verify(platformClients, never()).save(org.mockito.ArgumentMatchers.any());
    verifyNoInteractions(auditEvents);
  }
}
