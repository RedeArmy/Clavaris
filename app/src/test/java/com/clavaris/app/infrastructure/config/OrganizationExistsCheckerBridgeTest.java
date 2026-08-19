package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrganizationExistsCheckerBridgeTest {

  private final OrganizationRepository organizations = mock(OrganizationRepository.class);
  private final OrganizationExistsCheckerBridge bridge =
      new OrganizationExistsCheckerBridge(organizations);

  @Test
  void delegatesToOrganizationRepositoryExistsById() {
    UUID organizationId = UUID.randomUUID();
    when(organizations.existsById(organizationId)).thenReturn(true);

    assertThat(bridge.exists(organizationId)).isTrue();
  }

  @Test
  void returnsFalseWhenTheRepositoryDoes() {
    UUID organizationId = UUID.randomUUID();
    when(organizations.existsById(organizationId)).thenReturn(false);

    assertThat(bridge.exists(organizationId)).isFalse();
  }
}
