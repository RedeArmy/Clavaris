package com.clavaris.organization.application.usecase.createproductionenvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.createorganization.SigningKeyProvisioner;
import com.clavaris.organization.application.usecase.createorganization.SigningKeyProvisioner.ProvisionedSigningKey;
import com.clavaris.organization.domain.model.Organization;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CreateProductionEnvironmentServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private OrganizationRepository organizations;
  private SigningKeyProvisioner keyProvisioner;
  private AuditEventRecorder auditEvents;
  private CreateProductionEnvironmentService service;

  @BeforeEach
  void setUp() {
    organizations = mock(OrganizationRepository.class);
    keyProvisioner = mock(SigningKeyProvisioner.class);
    auditEvents = mock(AuditEventRecorder.class);
    service = new CreateProductionEnvironmentService(organizations, keyProvisioner, auditEvents);
  }

  private Organization developmentOrganization() {
    UUID ownerPlatformAccountId = UUID.randomUUID();
    Organization organization = Organization.register("JobSeeker", ownerPlatformAccountId);
    when(organizations.findById(organization.id())).thenReturn(Optional.of(organization));
    return organization;
  }

  @Test
  void createsAProductionSiblingLinkedBackToTheSourceDevelopmentOrganization() {
    Organization developmentOrganization = developmentOrganization();
    when(keyProvisioner.provisionFor(any()))
        .thenReturn(new ProvisionedSigningKey(UUID.randomUUID(), "a-kid", "RS256"));

    CreateProductionEnvironmentResult result =
        service.handle(
            new CreateProductionEnvironmentCommand(
                developmentOrganization.id(), "JobSeeker (production)", ACTOR));

    assertThat(result.organization().name()).isEqualTo("JobSeeker (production)");
    assertThat(result.organization().linkedEnvironmentOrganizationId())
        .contains(developmentOrganization.id());
    assertThat(result.organization().ownerPlatformAccountId())
        .isEqualTo(developmentOrganization.ownerPlatformAccountId());
  }

  @Test
  void savesBothTheNewProductionOrganizationAndTheUpdatedSourceOrganization() {
    Organization developmentOrganization = developmentOrganization();
    when(keyProvisioner.provisionFor(any()))
        .thenReturn(new ProvisionedSigningKey(UUID.randomUUID(), "a-kid", "RS256"));

    CreateProductionEnvironmentResult result =
        service.handle(
            new CreateProductionEnvironmentCommand(
                developmentOrganization.id(), "JobSeeker (production)", ACTOR));

    verify(organizations, times(2)).save(any());
    verify(organizations).save(result.organization());
    ArgumentCaptor<Organization> captor = ArgumentCaptor.forClass(Organization.class);
    verify(organizations, times(2)).save(captor.capture());
    Organization savedSource =
        captor.getAllValues().stream()
            .filter(candidate -> candidate.id().equals(developmentOrganization.id()))
            .findFirst()
            .orElseThrow();
    assertThat(savedSource.linkedEnvironmentOrganizationId()).contains(result.organization().id());
  }

  @Test
  void provisionsASigningKeyForTheNewProductionOrganization() {
    Organization developmentOrganization = developmentOrganization();
    when(keyProvisioner.provisionFor(any()))
        .thenReturn(new ProvisionedSigningKey(UUID.randomUUID(), "a-kid", "RS256"));

    CreateProductionEnvironmentResult result =
        service.handle(
            new CreateProductionEnvironmentCommand(
                developmentOrganization.id(), "JobSeeker (production)", ACTOR));

    verify(keyProvisioner).provisionFor(result.organization().id());
    assertThat(result.signingKey().kid()).isEqualTo("a-kid");
  }

  @Test
  void rejectsAnUnknownOrganizationWithoutPersistingAnything() {
    UUID unknownOrganizationId = UUID.randomUUID();
    when(organizations.findById(unknownOrganizationId)).thenReturn(Optional.empty());
    CreateProductionEnvironmentCommand command =
        new CreateProductionEnvironmentCommand(unknownOrganizationId, "Prod Co", ACTOR);

    assertThatExceptionOfType(OrganizationNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(organizations, never()).save(any());
    verifyNoInteractions(keyProvisioner);
    verifyNoInteractions(auditEvents);
  }

  @Test
  void rejectsAnAlreadyProductionOrganization() {
    Organization developmentOrganization = developmentOrganization();
    Organization alreadyPromoted =
        Organization.registerProductionEnvironment(
            "Already Prod", developmentOrganization.ownerPlatformAccountId(), UUID.randomUUID());
    when(organizations.findById(alreadyPromoted.id())).thenReturn(Optional.of(alreadyPromoted));
    CreateProductionEnvironmentCommand command =
        new CreateProductionEnvironmentCommand(alreadyPromoted.id(), "Prod Co", ACTOR);

    assertThatExceptionOfType(OrganizationNotDevelopmentException.class)
        .isThrownBy(() -> service.handle(command));

    verify(organizations, never()).save(any());
    verifyNoInteractions(keyProvisioner);
  }

  @Test
  void rejectsADevelopmentOrganizationThatAlreadyHasALinkedEnvironment() {
    Organization developmentOrganization =
        developmentOrganization().withLinkedEnvironmentOrganizationId(UUID.randomUUID());
    when(organizations.findById(developmentOrganization.id()))
        .thenReturn(Optional.of(developmentOrganization));
    CreateProductionEnvironmentCommand command =
        new CreateProductionEnvironmentCommand(developmentOrganization.id(), "Prod Co", ACTOR);

    assertThatExceptionOfType(OrganizationAlreadyHasLinkedEnvironmentException.class)
        .isThrownBy(() -> service.handle(command));

    verify(organizations, never()).save(any());
    verifyNoInteractions(keyProvisioner);
  }

  @Test
  void recordsAnAuditEventForTheNewProductionOrganization() {
    Organization developmentOrganization = developmentOrganization();
    when(keyProvisioner.provisionFor(any()))
        .thenReturn(new ProvisionedSigningKey(UUID.randomUUID(), "a-kid", "RS256"));

    CreateProductionEnvironmentResult result =
        service.handle(
            new CreateProductionEnvironmentCommand(
                developmentOrganization.id(), "JobSeeker (production)", ACTOR));

    verify(auditEvents)
        .write(
            ACTOR,
            "organization.production_environment_created",
            "Organization",
            result.organization().id().toString(),
            "developmentOrganizationId=" + developmentOrganization.id());
  }
}
