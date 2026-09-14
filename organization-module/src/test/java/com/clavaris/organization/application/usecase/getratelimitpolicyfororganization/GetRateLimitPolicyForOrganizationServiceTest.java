package com.clavaris.organization.application.usecase.getratelimitpolicyfororganization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.RateLimitPolicyRepository;
import com.clavaris.organization.domain.model.RateLimitPolicy;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GetRateLimitPolicyForOrganizationServiceTest {

  private static final int SYSTEM_DEFAULT_REQUESTS_PER_MINUTE = 600;
  private static final int HARD_SYSTEM_WIDE_CAP = 6000;

  @Test
  void returnsTheStoredCeilingMarkedCustomizedWhenAPolicyExists() {
    RateLimitPolicyRepository policies = mock(RateLimitPolicyRepository.class);
    UUID organizationId = UUID.randomUUID();
    RateLimitPolicy stored = RateLimitPolicy.define(organizationId, 1200, HARD_SYSTEM_WIDE_CAP);
    when(policies.findByOrganizationId(organizationId)).thenReturn(Optional.of(stored));
    GetRateLimitPolicyForOrganizationService service =
        new GetRateLimitPolicyForOrganizationService(
            policies, SYSTEM_DEFAULT_REQUESTS_PER_MINUTE, HARD_SYSTEM_WIDE_CAP);

    RateLimitPolicySnapshot result = service.handle(organizationId);

    assertThat(result.requestsPerMinute()).isEqualTo(1200);
    assertThat(result.customized()).isTrue();
    assertThat(result.updatedAt()).isEqualTo(stored.updatedAt());
    assertThat(result.hardCapRequestsPerMinute()).isEqualTo(HARD_SYSTEM_WIDE_CAP);
  }

  @Test
  void returnsTheSystemDefaultMarkedNotCustomizedWhenNoPolicyHasEverBeenSet() {
    RateLimitPolicyRepository policies = mock(RateLimitPolicyRepository.class);
    UUID organizationId = UUID.randomUUID();
    when(policies.findByOrganizationId(organizationId)).thenReturn(Optional.empty());
    GetRateLimitPolicyForOrganizationService service =
        new GetRateLimitPolicyForOrganizationService(
            policies, SYSTEM_DEFAULT_REQUESTS_PER_MINUTE, HARD_SYSTEM_WIDE_CAP);

    RateLimitPolicySnapshot result = service.handle(organizationId);

    assertThat(result.requestsPerMinute()).isEqualTo(SYSTEM_DEFAULT_REQUESTS_PER_MINUTE);
    assertThat(result.customized()).isFalse();
    assertThat(result.updatedAt())
        .as("no real row exists, so there is nothing real to timestamp")
        .isNull();
    assertThat(result.hardCapRequestsPerMinute()).isEqualTo(HARD_SYSTEM_WIDE_CAP);
  }
}
