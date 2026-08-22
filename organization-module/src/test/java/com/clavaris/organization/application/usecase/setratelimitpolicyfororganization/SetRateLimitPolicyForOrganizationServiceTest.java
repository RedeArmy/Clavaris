package com.clavaris.organization.application.usecase.setratelimitpolicyfororganization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.RateLimitPolicy;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SetRateLimitPolicyForOrganizationServiceTest {

  private static final int HARD_SYSTEM_WIDE_CAP = 6000;

  private OrganizationRepository organizations;
  private RateLimitPolicyRepository policies;
  private SetRateLimitPolicyForOrganizationService service;

  @BeforeEach
  void setUp() {
    organizations = mock(OrganizationRepository.class);
    policies = mock(RateLimitPolicyRepository.class);
    service =
        new SetRateLimitPolicyForOrganizationService(organizations, policies, HARD_SYSTEM_WIDE_CAP);
  }

  @Test
  void definesAFreshPolicyWhenNoneExistsYet() {
    UUID organizationId = UUID.randomUUID();
    when(organizations.existsById(organizationId)).thenReturn(true);
    when(policies.findByOrganizationId(organizationId)).thenReturn(Optional.empty());

    SetRateLimitPolicyForOrganizationResult result =
        service.handle(new SetRateLimitPolicyForOrganizationCommand(organizationId, 500));

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
        service.handle(new SetRateLimitPolicyForOrganizationCommand(organizationId, 900));

    assertThat(result.policy().id())
        .as("re-tuning must update the same row, never mint a second one for the same Organization")
        .isEqualTo(existing.id());
    assertThat(result.policy().requestsPerMinute()).isEqualTo(900);
  }

  @Test
  void rejectsANonExistentOrganizationWithoutPersistingAnything() {
    UUID nonExistentOrganizationId = UUID.randomUUID();
    when(organizations.existsById(nonExistentOrganizationId)).thenReturn(false);
    SetRateLimitPolicyForOrganizationCommand command =
        new SetRateLimitPolicyForOrganizationCommand(nonExistentOrganizationId, 500);

    assertThatExceptionOfType(OrganizationNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(policies, never()).save(any());
  }

  @Test
  void rejectsARequestsPerMinuteAboveTheHardSystemWideCapWithoutPersistingAnything() {
    UUID organizationId = UUID.randomUUID();
    when(organizations.existsById(organizationId)).thenReturn(true);
    when(policies.findByOrganizationId(organizationId)).thenReturn(Optional.empty());
    SetRateLimitPolicyForOrganizationCommand command =
        new SetRateLimitPolicyForOrganizationCommand(organizationId, HARD_SYSTEM_WIDE_CAP + 1);

    assertThatIllegalArgumentException().isThrownBy(() -> service.handle(command));

    verify(policies, never()).save(any());
  }
}
