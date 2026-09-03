package com.clavaris.identity.application.usecase.purgesigningkeyfororganization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.ActivateSigningKeyForOrganizationUseCase;
import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.SigningKeyRepository;
import com.clavaris.identity.application.usecase.rotatesigningkeyfororganization.SigningKeyMaterialGenerator;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SigningKey;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PurgeSigningKeyForOrganizationServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private SigningKeyRepository signingKeys;
  private SigningKeyMaterialGenerator keyMaterial;
  private ActivateSigningKeyForOrganizationUseCase activate;
  private AuditEventRecorder auditEvents;
  private PurgeSigningKeyForOrganizationService service;

  @BeforeEach
  void setUp() {
    signingKeys = mock(SigningKeyRepository.class);
    keyMaterial = mock(SigningKeyMaterialGenerator.class);
    activate = mock(ActivateSigningKeyForOrganizationUseCase.class);
    auditEvents = mock(AuditEventRecorder.class);
    service =
        new PurgeSigningKeyForOrganizationService(signingKeys, keyMaterial, activate, auditEvents);
  }

  @Test
  void purgingTheCurrentlyActiveKeyGeneratesAndActivatesAReplacementFirst() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    SigningKey compromisedActive = SigningKey.activate(organizationId, "compromised-kid", "RS256");
    when(signingKeys.findByKid(organizationId, "compromised-kid"))
        .thenReturn(Optional.of(compromisedActive));
    when(keyMaterial.generateFor(organizationId)).thenReturn("replacement-kid");

    PurgeSigningKeyForOrganizationResult result =
        service.handle(
            new PurgeSigningKeyForOrganizationCommand(organizationId, "compromised-kid", ACTOR));

    verify(activate).handle(organizationId, "replacement-kid", "RS256");
    assertThat(result.purgedKid()).isEqualTo("compromised-kid");
    assertThat(result.replacementKid()).isEqualTo("replacement-kid");
  }

  @Test
  void purgingTheActiveKeySavesItBackdatedToEpochAfterActivatingTheReplacement() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    SigningKey compromisedActive = SigningKey.activate(organizationId, "compromised-kid", "RS256");
    when(signingKeys.findByKid(organizationId, "compromised-kid"))
        .thenReturn(Optional.of(compromisedActive));
    when(keyMaterial.generateFor(organizationId)).thenReturn("replacement-kid");

    service.handle(
        new PurgeSigningKeyForOrganizationCommand(organizationId, "compromised-kid", ACTOR));

    ArgumentCaptor<SigningKey> saved = ArgumentCaptor.forClass(SigningKey.class);
    verify(signingKeys).save(saved.capture());
    assertThat(saved.getValue().kid()).isEqualTo("compromised-kid");
    assertThat(saved.getValue().retiredAt()).contains(Instant.EPOCH);
  }

  @Test
  void purgingAnAlreadyRetiredKeyNeverGeneratesOrActivatesAReplacement() {
    // Discovered compromised after the fact — the Organization's own separate, still-active key
    // (not this one) is untouched.
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    SigningKey oldRetiredKey = SigningKey.activate(organizationId, "old-kid", "RS256");
    oldRetiredKey.retire();
    when(signingKeys.findByKid(organizationId, "old-kid")).thenReturn(Optional.of(oldRetiredKey));

    PurgeSigningKeyForOrganizationResult result =
        service.handle(new PurgeSigningKeyForOrganizationCommand(organizationId, "old-kid", ACTOR));

    verifyNoInteractions(activate);
    verify(keyMaterial, never()).generateFor(any());
    assertThat(result.replacementKid()).isNull();
  }

  @Test
  void purgingAnAlreadyRetiredKeyStillBackdatesItToEpoch() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    SigningKey oldRetiredKey = SigningKey.activate(organizationId, "old-kid", "RS256");
    oldRetiredKey.retire();
    when(signingKeys.findByKid(organizationId, "old-kid")).thenReturn(Optional.of(oldRetiredKey));

    service.handle(new PurgeSigningKeyForOrganizationCommand(organizationId, "old-kid", ACTOR));

    ArgumentCaptor<SigningKey> saved = ArgumentCaptor.forClass(SigningKey.class);
    verify(signingKeys).save(saved.capture());
    assertThat(saved.getValue().retiredAt()).contains(Instant.EPOCH);
  }

  @Test
  void recordsADistinctAuditEventNamingBothThePurgedAndTheReplacementKid() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    SigningKey compromisedActive = SigningKey.activate(organizationId, "compromised-kid", "RS256");
    when(signingKeys.findByKid(organizationId, "compromised-kid"))
        .thenReturn(Optional.of(compromisedActive));
    when(keyMaterial.generateFor(organizationId)).thenReturn("replacement-kid");

    service.handle(
        new PurgeSigningKeyForOrganizationCommand(organizationId, "compromised-kid", ACTOR));

    verify(auditEvents)
        .write(
            ACTOR,
            "signing_key.emergency_purged",
            "Organization",
            organizationId.value().toString(),
            "purgedKid=compromised-kid replacementKid=replacement-kid");
  }

  @Test
  void rejectsAnUnknownKidWithoutGeneratingAnythingOrRecordingAnAuditEvent() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    when(signingKeys.findByKid(organizationId, "unknown-kid")).thenReturn(Optional.empty());
    PurgeSigningKeyForOrganizationCommand command =
        new PurgeSigningKeyForOrganizationCommand(organizationId, "unknown-kid", ACTOR);

    assertThatExceptionOfType(SigningKeyNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(keyMaterial, never()).generateFor(any());
    verifyNoInteractions(activate);
    verifyNoInteractions(auditEvents);
    verify(signingKeys, never()).save(any());
  }
}
