package com.clavaris.common.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditTenantResolver;
import com.clavaris.common.domain.model.AuditActor;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * TD-ARCH-038: the tenant key written with an audit event, and that recording never depends on
 * being able to work it out. No database: what is under test is what reaches {@code persist}.
 */
class JpaAuditEventRecorderTenantTest {

  private static final AuditActor ACTOR = AuditActor.platformAccount(UUID.randomUUID());

  private final EntityManager entityManager = mock(EntityManager.class);

  @SuppressWarnings("unchecked")
  private final ObjectProvider<AuditTenantResolver> provider = mock(ObjectProvider.class);

  private final JpaAuditEventRecorder recorder = new JpaAuditEventRecorder(entityManager, provider);

  private AuditEventEntity persisted() {
    final ArgumentCaptor<AuditEventEntity> captor = ArgumentCaptor.forClass(AuditEventEntity.class);
    verify(entityManager).persist(captor.capture());
    return captor.getValue();
  }

  @Test
  void storesTheOrganizationTheResolverFindsForTheTarget() {
    final UUID organizationId = UUID.randomUUID();
    final AuditTenantResolver resolver = mock(AuditTenantResolver.class);
    when(resolver.organizationOf("Workspace", "ws-1")).thenReturn(Optional.of(organizationId));
    when(provider.getIfAvailable()).thenReturn(resolver);

    recorder.write(ACTOR, "workspace.created", "Workspace", "ws-1", null);

    assertThat(persisted().getOrganizationId()).isEqualTo(organizationId);
  }

  @Test
  void anExplicitOrganizationIsUsedAndTheResolverIsNotAsked() {
    final UUID organizationId = UUID.randomUUID();
    final AuditTenantResolver resolver = mock(AuditTenantResolver.class);
    when(provider.getIfAvailable()).thenReturn(resolver);

    recorder.write(ACTOR, "workspace_role.deleted", "WorkspaceRole", "r-1", null, organizationId);

    assertThat(persisted().getOrganizationId()).isEqualTo(organizationId);
    org.mockito.Mockito.verifyNoInteractions(resolver);
  }

  @Test
  void aTargetBelongingToNoOrganizationIsStoredWithoutOne() {
    final AuditTenantResolver resolver = mock(AuditTenantResolver.class);
    when(resolver.organizationOf(any(), any())).thenReturn(Optional.empty());
    when(provider.getIfAvailable()).thenReturn(resolver);

    recorder.write(ACTOR, "platform_client.rotated", "PlatformClient", "pc-1", null);

    assertThat(persisted().getOrganizationId()).isNull();
  }

  @Test
  void withNoResolverAtAllTheEventIsStillRecorded() {
    when(provider.getIfAvailable()).thenReturn(null);

    recorder.write(ACTOR, "organization.created", "Organization", "org-1", null);

    assertThat(persisted().getOrganizationId()).isNull();
  }

  @Test
  void aResolverThatCannotReadTheDatabaseNeverCostsTheEvent() {
    final AuditTenantResolver resolver = mock(AuditTenantResolver.class);
    when(resolver.organizationOf(any(), any()))
        .thenThrow(new DataAccessResourceFailureException("down"));
    when(provider.getIfAvailable()).thenReturn(resolver);

    recorder.write(ACTOR, "workspace.created", "Workspace", "ws-1", null);

    assertThat(persisted().getAction()).isEqualTo("workspace.created");
    assertThat(persisted().getOrganizationId()).isNull();
  }

  @Test
  void anEventWithNoTargetIdIsNotResolved() {
    final AuditTenantResolver resolver = mock(AuditTenantResolver.class);
    when(provider.getIfAvailable()).thenReturn(resolver);

    recorder.write(ACTOR, "platform.something", "PlatformClient", null, null);

    assertThat(persisted().getOrganizationId()).isNull();
    org.mockito.Mockito.verifyNoInteractions(resolver);
  }
}
