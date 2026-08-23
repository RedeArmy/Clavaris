package com.clavaris.organization.application.usecase.setratelimitpolicyfororganization;

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
import com.clavaris.organization.domain.model.RateLimitPolicy;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SetRateLimitPolicyForOrganizationServiceTest {

  private static final int HARD_SYSTEM_WIDE_CAP = 6000;
  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private OrganizationRepository organizations;
  private RateLimitPolicyRepository policies;
  private AuditEventRecorder auditEvents;
  private SetRateLimitPolicyForOrganizationService service;

  @BeforeEach
  void setUp() {
    organizations = mock(OrganizationRepository.class);
    policies = mock(RateLimitPolicyRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    service =
        new SetRateLimitPolicyForOrganizationService(
            organizations, policies, HARD_SYSTEM_WIDE_CAP, auditEvents);
  }

  @Test
  void definesAFreshPolicyWhenNoneExistsYet() {
    UUID organizationId = UUID.randomUUID();
    when(organizations.existsById(organizationId)).thenReturn(true);
    when(policies.findByOrganizationId(organizationId)).thenReturn(Optional.empty());

    SetRateLimitPolicyForOrganizationResult result =
        service.handle(new SetRateLimitPolicyForOrganizationCommand(organizationId, 500, ACTOR));

    assertThat(result.policy().organizationId()).isEqualTo(organizationId);
    assertThat(result.policy().requestsPerMinute()).isEqualTo(500);
    verify(policies).save(result.policy());
  }

  @Test
  void updatesAnExistingPolicyInPlaceRatherThanCreatingASecondOne() {
    UUID organizationId = UUID.randomUUID();
    RateLimitPolicy existing = RateLimitPolicy.define(organizationId, 500, HARD_SYSTEM_WIDE_CAP);
    when(organizations.existsById(organizationId)).thenReturn(true);
    when(policies.findByOrganizationId(organizationId)).thenReturn(Optional.of(existing));

    SetRateLimitPolicyForOrganizationResult result =
        service.handle(new SetRateLimitPolicyForOrganizationCommand(organizationId, 900, ACTOR));

    assertThat(result.policy().id())
        .as("re-tuning must update the same row, never mint a second one for the same Organization")
        .isEqualTo(existing.id());
    assertThat(result.policy().requestsPerMinute()).isEqualTo(900);
  }

  // TD-SEC-007: named explicitly in the technical-debt register as a hard blocking dependency for
  // v1.1 self-service tuning (TD-FUT-002) — a real regression test, not just "the code compiles".
  @Test
  void recordsAnAuditEventForTheChange() {
    UUID organizationId = UUID.randomUUID();
    when(organizations.existsById(organizationId)).thenReturn(true);
    when(policies.findByOrganizationId(organizationId)).thenReturn(Optional.empty());

    service.handle(new SetRateLimitPolicyForOrganizationCommand(organizationId, 500, ACTOR));

    verify(auditEvents)
        .record(
            eq(ACTOR),
            eq("rate_limit_policy.set"),
            eq("Organization"),
            eq(organizationId.toString()),
            any());
  }

  @Test
  void rejectsANonExistentOrganizationWithoutPersistingAnything() {
    UUID nonExistentOrganizationId = UUID.randomUUID();
    when(organizations.existsById(nonExistentOrganizationId)).thenReturn(false);
    SetRateLimitPolicyForOrganizationCommand command =
        new SetRateLimitPolicyForOrganizationCommand(nonExistentOrganizationId, 500, ACTOR);

    assertThatExceptionOfType(OrganizationNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(policies, never()).save(any());
    verifyNoInteractions(auditEvents);
  }

  @Test
  void rejectsARequestsPerMinuteAboveTheHardSystemWideCapWithoutPersistingAnything() {
    UUID organizationId = UUID.randomUUID();
    when(organizations.existsById(organizationId)).thenReturn(true);
    when(policies.findByOrganizationId(organizationId)).thenReturn(Optional.empty());
    SetRateLimitPolicyForOrganizationCommand command =
        new SetRateLimitPolicyForOrganizationCommand(
            organizationId, HARD_SYSTEM_WIDE_CAP + 1, ACTOR);

    assertThatIllegalArgumentException().isThrownBy(() -> service.handle(command));

    verify(policies, never()).save(any());
    verifyNoInteractions(auditEvents);
  }
}
