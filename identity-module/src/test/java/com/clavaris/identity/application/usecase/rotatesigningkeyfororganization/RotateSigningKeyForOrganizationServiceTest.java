package com.clavaris.identity.application.usecase.rotatesigningkeyfororganization;

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
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SigningKey;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RotateSigningKeyForOrganizationServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private SigningKeyRepository signingKeys;
  private SigningKeyMaterialGenerator keyMaterial;
  private ActivateSigningKeyForOrganizationUseCase activate;
  private AuditEventRecorder auditEvents;
  private RotateSigningKeyForOrganizationService service;

  @BeforeEach
  void setUp() {
    signingKeys = mock(SigningKeyRepository.class);
    keyMaterial = mock(SigningKeyMaterialGenerator.class);
    activate = mock(ActivateSigningKeyForOrganizationUseCase.class);
    auditEvents = mock(AuditEventRecorder.class);
    service =
        new RotateSigningKeyForOrganizationService(signingKeys, keyMaterial, activate, auditEvents);
  }

  @Test
  void generatesNewMaterialThenActivatesItThroughTheExistingRetireAndActivateOperation() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    SigningKey previouslyActive = SigningKey.activate(organizationId, "old-kid", "RS256");
    when(signingKeys.findActive(organizationId)).thenReturn(Optional.of(previouslyActive));
    when(keyMaterial.generateFor(organizationId)).thenReturn("new-kid");
    SigningKey newlyActivated = SigningKey.activate(organizationId, "new-kid", "RS256");
    when(activate.handle(organizationId, "new-kid", "RS256")).thenReturn(newlyActivated);

    RotateSigningKeyForOrganizationResult result =
        service.handle(new RotateSigningKeyForOrganizationCommand(organizationId, ACTOR));

    assertThat(result.newKey()).isEqualTo(newlyActivated);
    assertThat(result.previousKid()).isEqualTo("old-kid");
    verify(activate).handle(organizationId, "new-kid", "RS256");
  }

  // TD-SEC-007: this is exactly the action the technical-debt register named as needing to be
  // audited before the rotation endpoint could go live at all.
  @Test
  void recordsAnAuditEventNamingBothTheNewAndThePreviousKid() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    SigningKey previouslyActive = SigningKey.activate(organizationId, "old-kid", "RS256");
    when(signingKeys.findActive(organizationId)).thenReturn(Optional.of(previouslyActive));
    when(keyMaterial.generateFor(organizationId)).thenReturn("new-kid");
    when(activate.handle(organizationId, "new-kid", "RS256"))
        .thenReturn(SigningKey.activate(organizationId, "new-kid", "RS256"));

    service.handle(new RotateSigningKeyForOrganizationCommand(organizationId, ACTOR));

    verify(auditEvents)
        .write(
            ACTOR,
            "signing_key.rotated",
            "Organization",
            organizationId.value().toString(),
            "newKid=new-kid previousKid=old-kid");
  }

  @Test
  void rejectsAnOrganizationWithNoActiveKeyWithoutGeneratingAnythingOrRecordingAnAuditEvent() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    when(signingKeys.findActive(organizationId)).thenReturn(Optional.empty());
    RotateSigningKeyForOrganizationCommand command =
        new RotateSigningKeyForOrganizationCommand(organizationId, ACTOR);

    assertThatExceptionOfType(NoActiveSigningKeyException.class)
        .isThrownBy(() -> service.handle(command));

    verify(keyMaterial, never()).generateFor(any());
    verifyNoInteractions(activate);
    verifyNoInteractions(auditEvents);
  }
}
