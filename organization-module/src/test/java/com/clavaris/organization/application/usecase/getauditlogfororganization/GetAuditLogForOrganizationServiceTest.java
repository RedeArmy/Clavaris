package com.clavaris.organization.application.usecase.getauditlogfororganization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventReader;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.domain.model.AuditEvent;
import com.clavaris.common.domain.model.AuditEventTargetRef;
import com.clavaris.organization.application.usecase.listworkspacemembers.ListWorkspaceMembersUseCase;
import com.clavaris.organization.application.usecase.listworkspacesfororganization.ListWorkspacesForOrganizationUseCase;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GetAuditLogForOrganizationServiceTest {

  @Test
  void resolvesEveryOwnedResourceIntoATargetRefBeforeQuerying() {
    ListWorkspacesForOrganizationUseCase listWorkspaces =
        mock(ListWorkspacesForOrganizationUseCase.class);
    ListWorkspaceMembersUseCase listMembers = mock(ListWorkspaceMembersUseCase.class);
    OAuthClientIdsForAuditLogProvider oauthClientIds =
        mock(OAuthClientIdsForAuditLogProvider.class);
    WebhookEndpointIdsForAuditLogProvider webhookEndpointIds =
        mock(WebhookEndpointIdsForAuditLogProvider.class);
    AuditEventReader auditEvents = mock(AuditEventReader.class);

    UUID organizationId = UUID.randomUUID();
    Workspace workspace = Workspace.register(organizationId, "Engineering");
    WorkspaceMembership membership =
        WorkspaceMembership.join(workspace.id(), UUID.randomUUID(), WorkspaceRole.MEMBER);
    String oauthClientId = UUID.randomUUID().toString();
    String webhookEndpointId = UUID.randomUUID().toString();

    when(listWorkspaces.handle(any())).thenReturn(List.of(workspace));
    when(listMembers.handle(any())).thenReturn(List.of(membership));
    when(oauthClientIds.oauthClientIds(organizationId)).thenReturn(List.of(oauthClientId));
    when(webhookEndpointIds.webhookEndpointIds(organizationId))
        .thenReturn(List.of(webhookEndpointId));
    AuditEvent expectedEvent =
        AuditEvent.of(
            AuditActor.platformAccount(UUID.randomUUID()),
            "organization.created",
            "Organization",
            organizationId.toString(),
            null);
    when(auditEvents.findRecentForTargets(any(), eq(100))).thenReturn(List.of(expectedEvent));

    GetAuditLogForOrganizationService service =
        new GetAuditLogForOrganizationService(
            listWorkspaces, listMembers, oauthClientIds, webhookEndpointIds, auditEvents);

    List<AuditEvent> result = service.handle(organizationId);

    assertThat(result).containsExactly(expectedEvent);
    verify(auditEvents)
        .findRecentForTargets(
            List.of(
                new AuditEventTargetRef("Organization", organizationId.toString()),
                new AuditEventTargetRef("Workspace", workspace.id().toString()),
                new AuditEventTargetRef("WorkspaceMembership", membership.id().toString()),
                new AuditEventTargetRef("OAuthClient", oauthClientId),
                new AuditEventTargetRef("WebhookEndpoint", webhookEndpointId)),
            100);
  }

  @Test
  void alwaysIncludesTheOrganizationsOwnTargetRefEvenWithNoOwnedResourcesAtAll() {
    ListWorkspacesForOrganizationUseCase listWorkspaces =
        mock(ListWorkspacesForOrganizationUseCase.class);
    ListWorkspaceMembersUseCase listMembers = mock(ListWorkspaceMembersUseCase.class);
    OAuthClientIdsForAuditLogProvider oauthClientIds =
        mock(OAuthClientIdsForAuditLogProvider.class);
    WebhookEndpointIdsForAuditLogProvider webhookEndpointIds =
        mock(WebhookEndpointIdsForAuditLogProvider.class);
    AuditEventReader auditEvents = mock(AuditEventReader.class);

    UUID organizationId = UUID.randomUUID();
    when(listWorkspaces.handle(any())).thenReturn(List.of());
    when(oauthClientIds.oauthClientIds(organizationId)).thenReturn(List.of());
    when(webhookEndpointIds.webhookEndpointIds(organizationId)).thenReturn(List.of());
    when(auditEvents.findRecentForTargets(any(), eq(100))).thenReturn(List.of());

    GetAuditLogForOrganizationService service =
        new GetAuditLogForOrganizationService(
            listWorkspaces, listMembers, oauthClientIds, webhookEndpointIds, auditEvents);

    service.handle(organizationId);

    verify(auditEvents)
        .findRecentForTargets(
            List.of(new AuditEventTargetRef("Organization", organizationId.toString())), 100);
  }
}
