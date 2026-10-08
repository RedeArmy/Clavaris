package com.clavaris.organization.application.usecase.setsessionpolicyfororganization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.SessionPolicy;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SetSessionPolicyForOrganizationServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private OrganizationRepository organizations;
  private SessionPolicyRepository policies;
  private AuditEventRecorder auditEvents;
  private SetSessionPolicyForOrganizationService service;

  @BeforeEach
  void setUp() {
    organizations = mock(OrganizationRepository.class);
    policies = mock(SessionPolicyRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    service = new SetSessionPolicyForOrganizationService(organizations, policies, auditEvents);
  }

  @Test
  void definesAFreshPolicyWhenNoneExistsYet() {
    UUID organizationId = UUID.randomUUID();
    when(organizations.existsById(organizationId)).thenReturn(true);
    when(policies.findByOrganizationId(organizationId)).thenReturn(Optional.empty());

    SetSessionPolicyForOrganizationResult result =
        service.handle(
            new SetSessionPolicyForOrganizationCommand(
                organizationId, 20_160, 1_440, 5, false, ACTOR));

    assertThat(result.policy().organizationId()).isEqualTo(organizationId);
    assertThat(result.policy().maximumLifetimeMinutes()).isEqualTo(20_160);
    assertThat(result.policy().inactivityTimeoutMinutes()).isEqualTo(1_440);
    assertThat(result.policy().reverificationWindowMinutes()).isEqualTo(5);
    assertThat(result.policy().multiSessionHandlingEnabled()).isFalse();
    verify(policies).save(result.policy());
  }

  @Test
  void updatesAnExistingPolicyInPlaceRatherThanCreatingASecondOne() {
    UUID organizationId = UUID.randomUUID();
    SessionPolicy existing = SessionPolicy.define(organizationId, 10_080, 10_080, 10, true);
    when(organizations.existsById(organizationId)).thenReturn(true);
    when(policies.findByOrganizationId(organizationId)).thenReturn(Optional.of(existing));

    SetSessionPolicyForOrganizationResult result =
        service.handle(
            new SetSessionPolicyForOrganizationCommand(
                organizationId, 20_160, 1_440, 5, false, ACTOR));

    assertThat(result.policy().id())
        .as("re-tuning must update the same row, never mint a second one for the same Organization")
        .isEqualTo(existing.id());
    assertThat(result.policy().maximumLifetimeMinutes()).isEqualTo(20_160);
  }

  @Test
  void recordsAnAuditEventForTheChange() {
    UUID organizationId = UUID.randomUUID();
    when(organizations.existsById(organizationId)).thenReturn(true);
    when(policies.findByOrganizationId(organizationId)).thenReturn(Optional.empty());

    service.handle(
        new SetSessionPolicyForOrganizationCommand(organizationId, 20_160, 1_440, 5, false, ACTOR));

    verify(auditEvents)
        .write(
            eq(ACTOR),
            eq("session_policy.set"),
            eq("Organization"),
            eq(organizationId.toString()),
            any());
  }

  @Test
  void rejectsANonExistentOrganizationWithoutPersistingAnything() {
    UUID nonExistentOrganizationId = UUID.randomUUID();
    when(organizations.existsById(nonExistentOrganizationId)).thenReturn(false);
    SetSessionPolicyForOrganizationCommand command =
        new SetSessionPolicyForOrganizationCommand(
            nonExistentOrganizationId, 10_080, 10_080, 10, true, ACTOR);

    assertThatExceptionOfType(OrganizationNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(policies, never()).save(any());
    verifyNoInteractions(auditEvents);
  }

  @Test
  void rejectsAnOutOfRangeValueWithoutPersistingAnything() {
    UUID organizationId = UUID.randomUUID();
    when(organizations.existsById(organizationId)).thenReturn(true);
    when(policies.findByOrganizationId(organizationId)).thenReturn(Optional.empty());
    SetSessionPolicyForOrganizationCommand command =
        new SetSessionPolicyForOrganizationCommand(organizationId, 10_080, 10_080, 11, true, ACTOR);

    assertThatIllegalArgumentException().isThrownBy(() -> service.handle(command));

    verify(policies, never()).save(any());
    verifyNoInteractions(auditEvents);
  }
}
