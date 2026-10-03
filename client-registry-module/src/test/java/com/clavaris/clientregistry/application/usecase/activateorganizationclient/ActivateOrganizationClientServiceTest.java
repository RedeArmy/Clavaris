package com.clavaris.clientregistry.application.usecase.activateorganizationclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.ArgumentCaptor;

class ActivateOrganizationClientServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformAccount(UUID.randomUUID());

  private final UUID organizationId = UUID.randomUUID();
  private OrganizationClientRepository organizationClients;
  private AuditEventRecorder auditEvents;
  private ActivateOrganizationClientService service;

  @BeforeEach
  void setUp() {
    organizationClients = mock(OrganizationClientRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    service = new ActivateOrganizationClientService(organizationClients, auditEvents);
  }

  private OrganizationClient deactivatedClient() {
    return OrganizationClient.register(
            organizationId,
            "sk_test_target",
            "hashed-secret",
            List.of(PlatformScopes.WORKSPACES_WRITE))
        .deactivate();
  }

  @Test
  void savesTheClientAsActiveWithItsSecretUntouched() {
    OrganizationClient existing = deactivatedClient();
    when(organizationClients.findByClientId("sk_test_target")).thenReturn(Optional.of(existing));

    service.handle(new ActivateOrganizationClientCommand("sk_test_target", organizationId, ACTOR));

    ArgumentCaptor<OrganizationClient> saved = forClass(OrganizationClient.class);
    verify(organizationClients).save(saved.capture());
    assertThat(saved.getValue().active()).isTrue();
    assertThat(saved.getValue().clientSecretHash()).isEqualTo(existing.clientSecretHash());
    assertThat(saved.getValue().id()).isEqualTo(existing.id());
  }

  @Test
  void recordsAnAuditEvent() {
    when(organizationClients.findByClientId("sk_test_target"))
        .thenReturn(Optional.of(deactivatedClient()));

    service.handle(new ActivateOrganizationClientCommand("sk_test_target", organizationId, ACTOR));

    verify(auditEvents)
        .write(
            ACTOR,
            "organization_client.activated",
            "Organization",
            organizationId.toString(),
            "clientId=sk_test_target");
  }

  @Test
  void rejectsAnUnknownClientIdOrOneOfAnotherOrganization() {
    when(organizationClients.findByClientId("sk_test_target"))
        .thenReturn(Optional.of(deactivatedClient()));
    ActivateOrganizationClientCommand otherTenant =
        new ActivateOrganizationClientCommand("sk_test_target", UUID.randomUUID(), ACTOR);
    ActivateOrganizationClientCommand unknown =
        new ActivateOrganizationClientCommand("ghost", organizationId, ACTOR);

    assertThatExceptionOfType(OrganizationClientNotFoundException.class)
        .isThrownBy(() -> service.handle(otherTenant));
    assertThatExceptionOfType(OrganizationClientNotFoundException.class)
        .isThrownBy(() -> service.handle(unknown));

    verify(organizationClients, never()).save(any());
    verifyNoInteractions(auditEvents);
  }
}
