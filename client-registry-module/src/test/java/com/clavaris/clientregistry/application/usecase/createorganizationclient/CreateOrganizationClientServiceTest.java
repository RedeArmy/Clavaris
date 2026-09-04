package com.clavaris.clientregistry.application.usecase.createorganizationclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.ClientSecretHasher;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.OrganizationEnvironmentChecker;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.OrganizationExistsChecker;
import com.clavaris.clientregistry.domain.model.PlatformScopes;
import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CreateOrganizationClientServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private OrganizationClientRepository organizationClients;
  private OrganizationExistsChecker orgExistsChecker;
  private OrganizationEnvironmentChecker environmentChecker;
  private ClientSecretHasher hasher;
  private OrganizationClientSecretGenerator secretGenerator;
  private AuditEventRecorder auditEvents;
  private CreateOrganizationClientService service;

  @BeforeEach
  void setUp() {
    organizationClients = mock(OrganizationClientRepository.class);
    orgExistsChecker = mock(OrganizationExistsChecker.class);
    environmentChecker = mock(OrganizationEnvironmentChecker.class);
    hasher = mock(ClientSecretHasher.class);
    secretGenerator = mock(OrganizationClientSecretGenerator.class);
    auditEvents = mock(AuditEventRecorder.class);
    service =
        new CreateOrganizationClientService(
            organizationClients,
            orgExistsChecker,
            environmentChecker,
            hasher,
            secretGenerator,
            auditEvents);
  }

  @Test
  void mintsADevelopmentPrefixedClientForADevelopmentOrganization() {
    UUID organizationId = UUID.randomUUID();
    when(orgExistsChecker.exists(organizationId)).thenReturn(true);
    when(environmentChecker.isDevelopment(organizationId)).thenReturn(true);
    when(secretGenerator.generate()).thenReturn("raw-secret");
    when(hasher.hash("raw-secret")).thenReturn("hashed-secret");

    CreateOrganizationClientResult result =
        service.handle(
            new CreateOrganizationClientCommand(
                organizationId, List.of(PlatformScopes.ACCOUNTS_IMPERSONATE), ACTOR));

    assertThat(result.organizationClient().clientId()).startsWith("sk_test_");
    assertThat(result.organizationClient().clientSecretHash()).isEqualTo("hashed-secret");
    assertThat(result.rawClientSecret()).isEqualTo("raw-secret");
    verify(organizationClients).save(result.organizationClient());
  }

  @Test
  void mintsAProductionPrefixedClientForAProductionOrganization() {
    UUID organizationId = UUID.randomUUID();
    when(orgExistsChecker.exists(organizationId)).thenReturn(true);
    when(environmentChecker.isDevelopment(organizationId)).thenReturn(false);
    when(secretGenerator.generate()).thenReturn("raw-secret");
    when(hasher.hash("raw-secret")).thenReturn("hashed-secret");

    CreateOrganizationClientResult result =
        service.handle(new CreateOrganizationClientCommand(organizationId, List.of(), ACTOR));

    assertThat(result.organizationClient().clientId()).startsWith("sk_live_");
  }

  @Test
  void rejectsANonExistentOrganization() {
    UUID organizationId = UUID.randomUUID();
    when(orgExistsChecker.exists(organizationId)).thenReturn(false);
    CreateOrganizationClientCommand command =
        new CreateOrganizationClientCommand(organizationId, List.of(), ACTOR);

    assertThatExceptionOfType(OrganizationNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(organizationClients, never()).save(any());
    verifyNoInteractions(auditEvents, secretGenerator, hasher);
  }

  @Test
  void recordsAnAuditEventForTheCreation() {
    UUID organizationId = UUID.randomUUID();
    when(orgExistsChecker.exists(organizationId)).thenReturn(true);
    when(secretGenerator.generate()).thenReturn("raw-secret");
    when(hasher.hash("raw-secret")).thenReturn("hashed-secret");

    service.handle(new CreateOrganizationClientCommand(organizationId, List.of(), ACTOR));

    verify(auditEvents)
        .write(
            eq(ACTOR),
            eq("organization_client.created"),
            eq("Organization"),
            eq(organizationId.toString()),
            any());
  }
}
