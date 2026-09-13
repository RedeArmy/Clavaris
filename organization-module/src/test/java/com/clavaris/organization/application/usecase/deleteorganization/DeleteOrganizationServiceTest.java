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
import com.clavaris.organization.domain.model.OrganizationEnvironment;
import java.time.Instant;
import java.util.List;
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
  private OrganizationWebhookDataEraser webhookDataEraser;
  private AuditEventRecorder auditEvents;
  private EventOutboxWriter outbox;
  private DeleteOrganizationService service;

  @BeforeEach
  void setUp() {
    organizations = mock(OrganizationRepository.class);
    organizationTokenRevoker = mock(OrganizationTokenRevoker.class);
    identityDataEraser = mock(OrganizationIdentityDataEraser.class);
    oauthClientsEraser = mock(OrganizationOAuthClientsEraser.class);
    webhookDataEraser = mock(OrganizationWebhookDataEraser.class);
    auditEvents = mock(AuditEventRecorder.class);
    outbox = mock(EventOutboxWriter.class);
    service =
        new DeleteOrganizationService(
            organizations,
            organizationTokenRevoker,
            identityDataEraser,
            oauthClientsEraser,
            webhookDataEraser,
            auditEvents,
            outbox);
  }

  private Organization registeredOrganization(final UUID organizationId) {
    Organization organization =
        Organization.reconstitute(
            organizationId,
            "Acme",
            Instant.now(),
            UUID.randomUUID(),
            false,
            List.of(),
            OrganizationEnvironment.PRODUCTION,
            null);
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
  void erasesIdentityAndOAuthClientAndWebhookDataAndDeletesTheOrganizationRow() {
    UUID organizationId = UUID.randomUUID();
    registeredOrganization(organizationId);

    service.handle(new DeleteOrganizationCommand(organizationId, ACTOR));

    verify(identityDataEraser).eraseAllFor(organizationId);
    verify(oauthClientsEraser).eraseAllFor(organizationId);
    verify(webhookDataEraser).eraseAllFor(organizationId);
    verify(organizations).deleteById(organizationId);
  }

  // SDE-III review, 2026-09-13 — real bug found and closed: linked_environment_organization_id
  // is a self-referencing FK with no ON DELETE clause, so deleting either side of an already-
  // promoted pair without unlinking the survivor first would raise a raw foreign-key-violation.
  @Test
  void unlinksTheSurvivingSiblingBeforeDeletingAnAlreadyLinkedOrganization() {
    UUID organizationId = UUID.randomUUID();
    UUID siblingId = UUID.randomUUID();
    Organization organization =
        Organization.reconstitute(
            organizationId,
            "Acme (dev)",
            Instant.now(),
            UUID.randomUUID(),
            false,
            List.of(),
            OrganizationEnvironment.DEVELOPMENT,
            siblingId);
    Organization sibling =
        Organization.reconstitute(
            siblingId,
            "Acme",
            Instant.now(),
            organization.ownerPlatformAccountId(),
            false,
            List.of(),
            OrganizationEnvironment.PRODUCTION,
            organizationId);
    when(organizations.findById(organizationId)).thenReturn(Optional.of(organization));
    when(organizations.findById(siblingId)).thenReturn(Optional.of(sibling));

    service.handle(new DeleteOrganizationCommand(organizationId, ACTOR));

    ArgumentCaptor<Organization> saved = ArgumentCaptor.forClass(Organization.class);
    verify(organizations).save(saved.capture());
    assertThat(saved.getValue().id()).isEqualTo(siblingId);
    assertThat(saved.getValue().linkedEnvironmentOrganizationId()).isEmpty();
  }

  // DEVELOPMENT and PRODUCTION are fully independent account pools (ADR-0010) — a real user
  // requirement confirmed explicitly, not assumed: unlinking the surviving sibling must be a pure
  // pairing-metadata change, never a data operation. This test proves both halves of that
  // guarantee: (1) every other field on the sibling — name, owner, environment, social-login
  // config, createdAt — survives byte-for-byte identical, only the link itself changes; (2) none
  // of the three erasers (which is what would actually touch
  // Accounts/OAuthClients/WebhookEndpoints)
  // is ever invoked with the sibling's own id — they only ever run against the Organization
  // actually being deleted. Adding or removing an Account/Workspace/OAuthClient in one environment
  // already cannot reach the other (each is its own Organization row with its own account pool);
  // this test exists so that guarantee stays true after this delete-time unlink too, not just
  // "true by the rest of the codebase's own design."
  @Test
  void unlinkingTheSiblingNeverTouchesAnyOfItsOwnDataOnlyThePairingMetadata() {
    UUID organizationId = UUID.randomUUID();
    UUID siblingId = UUID.randomUUID();
    Instant siblingCreatedAt = Instant.now().minusSeconds(3600);
    UUID siblingOwnerId = UUID.randomUUID();
    Organization organization =
        Organization.reconstitute(
            organizationId,
            "Acme (dev)",
            Instant.now(),
            UUID.randomUUID(),
            false,
            List.of(),
            OrganizationEnvironment.DEVELOPMENT,
            siblingId);
    Organization sibling =
        Organization.reconstitute(
            siblingId,
            "Acme",
            siblingCreatedAt,
            siblingOwnerId,
            true,
            List.of("GOOGLE"),
            OrganizationEnvironment.PRODUCTION,
            organizationId);
    when(organizations.findById(organizationId)).thenReturn(Optional.of(organization));
    when(organizations.findById(siblingId)).thenReturn(Optional.of(sibling));

    service.handle(new DeleteOrganizationCommand(organizationId, ACTOR));

    ArgumentCaptor<Organization> saved = ArgumentCaptor.forClass(Organization.class);
    verify(organizations).save(saved.capture());
    Organization survivingSibling = saved.getValue();
    assertThat(survivingSibling.name()).isEqualTo("Acme");
    assertThat(survivingSibling.createdAt()).isEqualTo(siblingCreatedAt);
    assertThat(survivingSibling.ownerPlatformAccountId()).isEqualTo(siblingOwnerId);
    assertThat(survivingSibling.socialLoginEnabled()).isTrue();
    assertThat(survivingSibling.allowedSocialProviders()).containsExactly("GOOGLE");
    assertThat(survivingSibling.environment()).isEqualTo(OrganizationEnvironment.PRODUCTION);
    assertThat(survivingSibling.linkedEnvironmentOrganizationId())
        .as("only the pairing link itself changes")
        .isEmpty();

    // The real isolation guarantee: nothing that erases actual data (Accounts, OAuthClients,
    // WebhookEndpoints) is ever called with the sibling's own id — only with the Organization
    // actually being deleted.
    verify(identityDataEraser, never()).eraseAllFor(siblingId);
    verify(oauthClientsEraser, never()).eraseAllFor(siblingId);
    verify(webhookDataEraser, never()).eraseAllFor(siblingId);
    verify(organizationTokenRevoker, never()).revokeAllTokensFor(siblingId);
    verify(organizations, never()).deleteById(siblingId);
  }

  @Test
  void neverTouchesAnySiblingWhenTheOrganizationHasNoLinkedEnvironment() {
    UUID organizationId = UUID.randomUUID();
    registeredOrganization(organizationId);

    service.handle(new DeleteOrganizationCommand(organizationId, ACTOR));

    verify(organizations, never()).save(any());
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
    verify(outbox)
        .write(
            eq("Organization"),
            eq("organization.deleted"),
            eq(organizationId),
            eq(organizationId),
            event.capture());
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
    verifyNoInteractions(webhookDataEraser);
    verifyNoInteractions(auditEvents);
    verifyNoInteractions(outbox);
    verify(organizations, never()).deleteById(any());
  }
}
