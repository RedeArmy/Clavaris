package com.clavaris.organization.application.usecase.deleteorganization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.event.OrganizationDeletedEvent;
import com.clavaris.organization.domain.model.Organization;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class DeleteOrganizationServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private OrganizationRepository organizations;
  private OrganizationTokenRevoker organizationTokenRevoker;
  private OrganizationIdentityDataEraser identityDataEraser;
  private OrganizationOAuthClientsEraser oauthClientsEraser;
  private AuditEventRecorder auditEvents;
  private EventOutboxWriter outbox;
  private DeleteOrganizationService service;

  @BeforeEach
  void setUp() {
    organizations = mock(OrganizationRepository.class);
    organizationTokenRevoker = mock(OrganizationTokenRevoker.class);
    identityDataEraser = mock(OrganizationIdentityDataEraser.class);
    oauthClientsEraser = mock(OrganizationOAuthClientsEraser.class);
    auditEvents = mock(AuditEventRecorder.class);
    outbox = mock(EventOutboxWriter.class);
    service =
        new DeleteOrganizationService(
            organizations,
            organizationTokenRevoker,
            identityDataEraser,
            oauthClientsEraser,
            auditEvents,
            outbox);
  }

  private Organization registeredOrganization(final UUID organizationId) {
    Organization organization =
        Organization.reconstitute(organizationId, "Acme", Instant.now(), UUID.randomUUID());
    when(organizations.findById(organizationId)).thenReturn(Optional.of(organization));
    return organization;
  }

  @Test
  void revokesTokensBeforeErasingSoTheRevokersOwnSubqueriesCanStillResolveTheRows() {
    UUID organizationId = UUID.randomUUID();
    registeredOrganization(organizationId);

    service.handle(new DeleteOrganizationCommand(organizationId, ACTOR));

    // Ordering is load-bearing, not incidental — OrganizationTokenRevokerBridge's own real
    // implementation queries accounts/oauth_clients by organizationId, which the erasers below
    // remove; verified here as an explicit sequence, not just "all were called."
    InOrder order = inOrder(organizationTokenRevoker, identityDataEraser, oauthClientsEraser);
    order.verify(organizationTokenRevoker).revokeAllTokensFor(organizationId);
    order.verify(identityDataEraser).eraseAllFor(organizationId);
    order.verify(oauthClientsEraser).eraseAllFor(organizationId);
  }

  @Test
  void erasesIdentityAndOAuthClientDataAndDeletesTheOrganizationRow() {
    UUID organizationId = UUID.randomUUID();
    registeredOrganization(organizationId);

    service.handle(new DeleteOrganizationCommand(organizationId, ACTOR));

    verify(identityDataEraser).eraseAllFor(organizationId);
    verify(oauthClientsEraser).eraseAllFor(organizationId);
    verify(organizations).deleteById(organizationId);
  }

  @Test
  void recordsAnAuditEventForTheDeletedOrganization() {
    UUID organizationId = UUID.randomUUID();
    registeredOrganization(organizationId);

    service.handle(new DeleteOrganizationCommand(organizationId, ACTOR));

    verify(auditEvents)
        .write(
            eq(ACTOR),
            eq("organization.deleted"),
            eq("Organization"),
            eq(organizationId.toString()),
            isNull());
  }

  @Test
  void writesAnOrganizationDeletedOutboxEvent() {
    // TD-ARCH-007: DeleteAccountService's own sibling behavior — this service never wrote one
    // before this fix.
    UUID organizationId = UUID.randomUUID();
    Organization organization = registeredOrganization(organizationId);

    service.handle(new DeleteOrganizationCommand(organizationId, ACTOR));

    ArgumentCaptor<OrganizationDeletedEvent> event =
        ArgumentCaptor.forClass(OrganizationDeletedEvent.class);
    verify(outbox).write(eq("organization.deleted"), eq(organizationId), event.capture());
    assertThat(event.getValue().organizationId()).isEqualTo(organizationId);
    assertThat(event.getValue().name()).isEqualTo(organization.name());
    assertThat(event.getValue().occurredAt()).isNotNull();
  }

  @Test
  void rejectsAnUnknownOrganizationWithoutRevokingOrDeletingAnything() {
    UUID unknownOrganizationId = UUID.randomUUID();
    when(organizations.findById(unknownOrganizationId)).thenReturn(Optional.empty());
    DeleteOrganizationCommand command = new DeleteOrganizationCommand(unknownOrganizationId, ACTOR);

    assertThatExceptionOfType(OrganizationNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verifyNoInteractions(organizationTokenRevoker);
    verifyNoInteractions(identityDataEraser);
    verifyNoInteractions(oauthClientsEraser);
    verifyNoInteractions(auditEvents);
    verifyNoInteractions(outbox);
    verify(organizations, never()).deleteById(any());
  }
}
