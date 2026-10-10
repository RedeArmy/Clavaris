package com.clavaris.app.infrastructure.adapter.out.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.Organization;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The Organization's name, for emails and the passkey prompt: trimmed, and absent when blank. */
class OrganizationNameProviderBridgeTest {

  private final OrganizationRepository organizations = mock(OrganizationRepository.class);
  private final OrganizationNameProviderBridge bridge =
      new OrganizationNameProviderBridge(organizations);

  private final UUID id = UUID.randomUUID();

  @Test
  void theNameOfAnExistingOrganization() {
    when(organizations.findById(id))
        .thenReturn(Optional.of(Organization.register("  Acme Analytics ", UUID.randomUUID())));

    assertThat(bridge.nameFor(new OrganizationId(id))).contains("Acme Analytics");
  }

  @Test
  void noNameForAnOrganizationThatCannotBeFound() {
    when(organizations.findById(id)).thenReturn(Optional.empty());

    assertThat(bridge.nameFor(new OrganizationId(id))).isEmpty();
  }
}
