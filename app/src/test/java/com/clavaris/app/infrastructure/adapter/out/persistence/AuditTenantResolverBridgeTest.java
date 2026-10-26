package com.clavaris.app.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** TD-ARCH-038: which Organization an audit event's target belongs to, kind by kind. */
class AuditTenantResolverBridgeTest {

  private final EntityManager entityManager = mock(EntityManager.class);
  private final AuditTenantResolverBridge bridge = new AuditTenantResolverBridge(entityManager);
  private final UUID organizationId = UUID.randomUUID();
  private final UUID targetId = UUID.randomUUID();

  /** A native query that returns {@code rows} when its parameter is {@code expectedParameter}. */
  private void queryReturns(
      final String sqlFragment, final Object expectedParameter, final List<UUID> rows) {
    final Query query = mock(Query.class);
    when(entityManager.createNativeQuery(contains(sqlFragment), eq(UUID.class))).thenReturn(query);
    when(query.setParameter(1, expectedParameter)).thenReturn(query);
    when(query.getResultList()).thenReturn(rows);
  }

  @Test
  void anOrganizationTargetIsItsOwnOrganizationWithoutAQuery() {
    assertThat(bridge.organizationOf("Organization", organizationId.toString()))
        .contains(organizationId);
    verifyNoInteractions(entityManager);
  }

  @Test
  void anOrganizationTargetThatIsNotAUuidResolvesToNothing() {
    assertThat(bridge.organizationOf("Organization", "org-1")).isEmpty();
  }

  @Test
  void aWorkspaceIsLookedUpInTheTableThatOwnsIt() {
    queryReturns("FROM workspaces", targetId, List.of(organizationId));

    assertThat(bridge.organizationOf("Workspace", targetId.toString())).contains(organizationId);
  }

  // A native query through the EntityManager, so Hibernate flushes pending writes first: the audit
  // row for a Workspace created a moment ago in the same transaction must still find it.
  @Test
  void lookupsGoThroughTheEntityManagerSoPendingWritesAreFlushedFirst() {
    queryReturns("FROM workspaces", targetId, List.of(organizationId));

    bridge.organizationOf("Workspace", targetId.toString());

    verify(entityManager).createNativeQuery(anyString(), eq(UUID.class));
  }

  @Test
  void aWorkspaceTeamAndAMembershipAreFoundThroughTheirWorkspace() {
    queryReturns("workspace_teams", targetId, List.of(organizationId));
    queryReturns("workspace_memberships", targetId, List.of(organizationId));

    assertThat(bridge.organizationOf("WorkspaceTeam", targetId.toString()))
        .contains(organizationId);
    assertThat(bridge.organizationOf("WorkspaceMembership", targetId.toString()))
        .contains(organizationId);
  }

  @Test
  void accountKindsAreFoundThroughTheAccount() {
    queryReturns("FROM accounts", targetId, List.of(organizationId));
    queryReturns("JOIN accounts", targetId, List.of(organizationId));

    assertThat(bridge.organizationOf("Account", targetId.toString())).contains(organizationId);
    assertThat(bridge.organizationOf("Session", targetId.toString())).contains(organizationId);
    assertThat(bridge.organizationOf("KnownDevice", targetId.toString())).contains(organizationId);
    assertThat(bridge.organizationOf("WebAuthnCredential", targetId.toString()))
        .contains(organizationId);
  }

  @Test
  void anOAuthClientIsFoundByItsClientIdString() {
    queryReturns("oauth_clients", "client_abc", List.of(organizationId));

    assertThat(bridge.organizationOf("OAuthClient", "client_abc")).contains(organizationId);
  }

  @Test
  void aTargetThatNoLongerExistsResolvesToNothing() {
    queryReturns("FROM workspaces", targetId, List.of());

    assertThat(bridge.organizationOf("Workspace", targetId.toString())).isEmpty();
  }

  @Test
  void platformLevelAndUnknownKindsBelongToNoOrganization() {
    assertThat(bridge.organizationOf("PlatformAccount", targetId.toString())).isEmpty();
    assertThat(bridge.organizationOf("PlatformClient", "bootstrap")).isEmpty();
    assertThat(bridge.organizationOf("SomethingNew", targetId.toString())).isEmpty();
    assertThat(bridge.organizationOf("Workspace", null)).isEmpty();
    verifyNoInteractions(entityManager);
  }

  @Test
  void aKindThatNeedsAUuidIsNotQueriedWithSomethingElse() {
    assertThat(bridge.organizationOf("Workspace", "not-a-uuid")).isEmpty();
    verifyNoInteractions(entityManager);
  }
}
