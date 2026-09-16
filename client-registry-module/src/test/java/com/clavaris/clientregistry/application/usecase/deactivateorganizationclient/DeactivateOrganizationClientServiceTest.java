package com.clavaris.clientregistry.application.usecase.deactivateorganizationclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientRepository;
import com.clavaris.clientregistry.domain.model.OrganizationClient;
import com.clavaris.clientregistry.domain.model.PlatformScopes;
import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SDE-III review, 2026-09-15: this class had no test coverage of its own before the organizationId
 * ownership check {@link DeactivateOrganizationClientCommand}'s own Javadoc documents — first real
 * coverage, not just a signature-compile fixup.
 */
class DeactivateOrganizationClientServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformAccount(UUID.randomUUID());

  private final UUID organizationId = UUID.randomUUID();
  private OrganizationClientRepository organizationClients;
  private AuditEventRecorder auditEvents;
  private DeactivateOrganizationClientService service;

  @BeforeEach
  void setUp() {
    organizationClients = mock(OrganizationClientRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    service = new DeactivateOrganizationClientService(organizationClients, auditEvents);
  }

  private OrganizationClient sampleClient() {
    return OrganizationClient.register(
        organizationId,
        "sk_test_target",
        "hashed-secret",
        List.of(PlatformScopes.WORKSPACES_WRITE));
  }

  @Test
  void savesTheClientWithActiveFalseWhenOrganizationIdIsPresentAndMatches() {
    OrganizationClient existing = sampleClient();
    assertThat(existing.active()).isTrue();
    when(organizationClients.findByClientId("sk_test_target")).thenReturn(Optional.of(existing));

    service.handle(
        new DeactivateOrganizationClientCommand("sk_test_target", organizationId, ACTOR));

    verify(organizationClients).save(argThat(saved -> !saved.active()));
  }

  // SDE-III review, 2026-09-15: the platform-tier REST endpoint's own deliberate, unscoped reach —
  // see DeactivateOrganizationClientCommand's own Javadoc for why null is a legitimate value here,
  // not a bug.
  @Test
  void savesTheClientWithActiveFalseWhenOrganizationIdIsNull() {
    OrganizationClient existing = sampleClient();
    when(organizationClients.findByClientId("sk_test_target")).thenReturn(Optional.of(existing));

    service.handle(new DeactivateOrganizationClientCommand("sk_test_target", null, ACTOR));

    verify(organizationClients).save(argThat(saved -> !saved.active()));
  }

  @Test
  void recordsAnAuditEvent() {
    OrganizationClient existing = sampleClient();
    when(organizationClients.findByClientId("sk_test_target")).thenReturn(Optional.of(existing));

    service.handle(
        new DeactivateOrganizationClientCommand("sk_test_target", organizationId, ACTOR));

    verify(auditEvents)
        .write(
            ACTOR,
            "organization_client.deactivated",
            "Organization",
            organizationId.toString(),
            "clientId=sk_test_target");
  }

  @Test
  void rejectsAnUnknownClientIdWithoutPersistingOrRecordingAnything() {
    when(organizationClients.findByClientId("sk_test_ghost")).thenReturn(Optional.empty());
    DeactivateOrganizationClientCommand command =
        new DeactivateOrganizationClientCommand("sk_test_ghost", organizationId, ACTOR);

    assertThatExceptionOfType(OrganizationClientNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(organizationClients, never()).save(any());
    verifyNoInteractions(auditEvents);
  }

  // SDE-III review, 2026-09-15 — the real regression this guards: before this fix, this method
  // never even accepted an organizationId, so a caller pairing a valid organizationId with a
  // different Organization's own clientId would deactivate that Organization's real client.
  @Test
  void rejectsAClientThatBelongsToADifferentOrganizationWithoutPersistingOrRecordingAnything() {
    OrganizationClient existing = sampleClient();
    when(organizationClients.findByClientId("sk_test_target")).thenReturn(Optional.of(existing));
    UUID unrelatedOrganizationId = UUID.randomUUID();
    DeactivateOrganizationClientCommand command =
        new DeactivateOrganizationClientCommand("sk_test_target", unrelatedOrganizationId, ACTOR);

    assertThatExceptionOfType(OrganizationClientNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(organizationClients, never()).save(any());
    verifyNoInteractions(auditEvents);
  }
}
