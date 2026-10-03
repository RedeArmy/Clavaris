package com.clavaris.clientregistry.application.usecase.deleteorganizationclient;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientRepository;
import com.clavaris.clientregistry.application.usecase.deleteoauthclient.OAuthClientTokenRevoker;
import com.clavaris.clientregistry.domain.model.OrganizationClient;
import com.clavaris.clientregistry.domain.model.PlatformScopes;
import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeleteOrganizationClientServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformAccount(UUID.randomUUID());

  private final UUID organizationId = UUID.randomUUID();
  private OrganizationClientRepository organizationClients;
  private OAuthClientTokenRevoker tokenRevoker;
  private AuditEventRecorder auditEvents;
  private DeleteOrganizationClientService service;

  @BeforeEach
  void setUp() {
    organizationClients = mock(OrganizationClientRepository.class);
    tokenRevoker = mock(OAuthClientTokenRevoker.class);
    auditEvents = mock(AuditEventRecorder.class);
    service = new DeleteOrganizationClientService(organizationClients, tokenRevoker, auditEvents);
  }

  private OrganizationClient activeClient() {
    return OrganizationClient.register(
        organizationId,
        "sk_test_target",
        "argon2id$hashed",
        List.of(PlatformScopes.WORKSPACES_WRITE));
  }

  @Test
  void revokesTokensThenDeletesTheDeactivatedClient() {
    OrganizationClient existing = activeClient().deactivate();
    when(organizationClients.findByClientId("sk_test_target")).thenReturn(Optional.of(existing));

    service.handle(new DeleteOrganizationClientCommand("sk_test_target", organizationId, ACTOR));

    verify(tokenRevoker).revokeAllTokensFor(existing.id());
    verify(organizationClients).delete(existing);
  }

  @Test
  void recordsAnAuditEventWithoutTheSecretHash() {
    OrganizationClient existing = activeClient().deactivate();
    when(organizationClients.findByClientId("sk_test_target")).thenReturn(Optional.of(existing));

    service.handle(new DeleteOrganizationClientCommand("sk_test_target", organizationId, ACTOR));

    verify(auditEvents)
        .write(
            ACTOR,
            "organization_client.deleted",
            "Organization",
            organizationId.toString(),
            "deletedClientId=sk_test_target");
  }

  @Test
  void rejectsAnUnknownClientIdWithoutDeletingOrRecordingAnything() {
    when(organizationClients.findByClientId("ghost")).thenReturn(Optional.empty());
    DeleteOrganizationClientCommand command =
        new DeleteOrganizationClientCommand("ghost", organizationId, ACTOR);

    assertThatExceptionOfType(OrganizationClientNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(organizationClients, never()).delete(any());
    verifyNoInteractions(tokenRevoker);
    verifyNoInteractions(auditEvents);
  }

  @Test
  void rejectsAClientOfAnotherOrganizationAsNotFound() {
    OrganizationClient existing = activeClient().deactivate();
    when(organizationClients.findByClientId("sk_test_target")).thenReturn(Optional.of(existing));
    DeleteOrganizationClientCommand command =
        new DeleteOrganizationClientCommand("sk_test_target", UUID.randomUUID(), ACTOR);

    assertThatExceptionOfType(OrganizationClientNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(organizationClients, never()).delete(any());
    verifyNoInteractions(tokenRevoker);
    verifyNoInteractions(auditEvents);
  }

  // The guard that makes deletion a two-step, deliberate act: deactivate first, then delete.
  @Test
  void rejectsAnActiveClientWithoutDeletingOrRecordingAnything() {
    OrganizationClient existing = activeClient();
    when(organizationClients.findByClientId("sk_test_target")).thenReturn(Optional.of(existing));
    DeleteOrganizationClientCommand command =
        new DeleteOrganizationClientCommand("sk_test_target", organizationId, ACTOR);

    assertThatExceptionOfType(OrganizationClientActiveException.class)
        .isThrownBy(() -> service.handle(command));

    verify(organizationClients, never()).delete(any());
    verifyNoInteractions(tokenRevoker);
    verifyNoInteractions(auditEvents);
  }
}
