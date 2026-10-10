package com.clavaris.app.infrastructure.adapter.out.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.getorganizationprofiles.GetOrganizationProfilesUseCase;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.OrganizationProfile;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The Organization's name, for emails and the passkey prompt: trimmed, and absent when blank. */
class OrganizationNameProviderBridgeTest {

  private final OrganizationRepository organizations = mock(OrganizationRepository.class);
  private final GetOrganizationProfilesUseCase getProfiles =
      mock(GetOrganizationProfilesUseCase.class);
  private final OrganizationNameProviderBridge bridge =
      new OrganizationNameProviderBridge(organizations, getProfiles);

  private final UUID id = UUID.randomUUID();

  // What the Organization chose to call its application wins, so the emails and the passkey prompt
  // say what the sign-in page says.
  @Test
  void theApplicationNameTheOrganizationChoseWinsOverItsOwnName() {
    when(getProfiles.handle(List.of(id)))
        .thenReturn(
            Map.of(id, OrganizationProfile.empty(id).withDetails(null, "  Acme Portal ", null)));
    when(organizations.findById(id))
        .thenReturn(Optional.of(Organization.register("Acme Analytics", UUID.randomUUID())));

    assertThat(bridge.nameFor(new OrganizationId(id))).contains("Acme Portal");
  }

  @Test
  void aProfileWithNoApplicationNameFallsBackToTheOrganizationsName() {
    when(getProfiles.handle(List.of(id)))
        .thenReturn(Map.of(id, OrganizationProfile.empty(id).withDetails("About us", null, null)));
    when(organizations.findById(id))
        .thenReturn(Optional.of(Organization.register("Acme Analytics", UUID.randomUUID())));

    assertThat(bridge.nameFor(new OrganizationId(id))).contains("Acme Analytics");
  }

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
