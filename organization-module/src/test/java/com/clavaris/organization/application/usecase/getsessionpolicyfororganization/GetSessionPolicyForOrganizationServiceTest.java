package com.clavaris.organization.application.usecase.getsessionpolicyfororganization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.organization.application.usecase.setsessionpolicyfororganization.SessionPolicyRepository;
import com.clavaris.organization.domain.model.SessionPolicy;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GetSessionPolicyForOrganizationServiceTest {

  @Test
  void returnsTheStoredPolicyWhenOneExists() {
    SessionPolicyRepository policies = mock(SessionPolicyRepository.class);
    UUID organizationId = UUID.randomUUID();
    SessionPolicy stored = SessionPolicy.define(organizationId, 20_160, 1_440, 5, false);
    when(policies.findByOrganizationId(organizationId)).thenReturn(Optional.of(stored));
    GetSessionPolicyForOrganizationService service =
        new GetSessionPolicyForOrganizationService(policies);

    SessionPolicy result = service.handle(organizationId);

    assertThat(result).isEqualTo(stored);
  }

  @Test
  void returnsTheSystemDefaultsWhenNoPolicyHasEverBeenSet() {
    SessionPolicyRepository policies = mock(SessionPolicyRepository.class);
    UUID organizationId = UUID.randomUUID();
    when(policies.findByOrganizationId(organizationId)).thenReturn(Optional.empty());
    GetSessionPolicyForOrganizationService service =
        new GetSessionPolicyForOrganizationService(policies);

    SessionPolicy result = service.handle(organizationId);

    assertThat(result.organizationId()).isEqualTo(organizationId);
    assertThat(result.maximumLifetimeMinutes()).isEqualTo(10_080);
    assertThat(result.inactivityTimeoutMinutes()).isEqualTo(10_080);
    assertThat(result.reverificationWindowMinutes()).isEqualTo(10);
    assertThat(result.multiSessionHandlingEnabled()).isTrue();
  }
}
