package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SocialProvider;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.Organization;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrganizationSocialLoginPolicyProviderBridgeTest {

  private final OrganizationRepository organizations = mock(OrganizationRepository.class);
  private final OrganizationSocialLoginPolicyProviderBridge bridge =
      new OrganizationSocialLoginPolicyProviderBridge(organizations);

  @Test
  void allowsAProviderTheOrganizationHasEnabled() {
    UUID organizationId = UUID.randomUUID();
    Organization organization =
        Organization.register("Acme", UUID.randomUUID())
            .withSocialLoginPolicy(true, List.of("GOOGLE"));
    when(organizations.findById(organizationId)).thenReturn(Optional.of(organization));

    assertThat(bridge.isProviderAllowed(new OrganizationId(organizationId), SocialProvider.GOOGLE))
        .isTrue();
  }

  @Test
  void rejectsAProviderNotInTheAllowedList() {
    UUID organizationId = UUID.randomUUID();
    Organization organization =
        Organization.register("Acme", UUID.randomUUID())
            .withSocialLoginPolicy(true, List.of("GOOGLE"));
    when(organizations.findById(organizationId)).thenReturn(Optional.of(organization));

    assertThat(bridge.isProviderAllowed(new OrganizationId(organizationId), SocialProvider.GITHUB))
        .isFalse();
  }

  @Test
  void rejectsEverythingWhenSocialLoginItselfIsDisabled() {
    UUID organizationId = UUID.randomUUID();
    Organization organization = Organization.register("Acme", UUID.randomUUID());
    when(organizations.findById(organizationId)).thenReturn(Optional.of(organization));

    assertThat(bridge.isProviderAllowed(new OrganizationId(organizationId), SocialProvider.GOOGLE))
        .isFalse();
  }

  @Test
  void treatsAnUnresolvableOrganizationAsNotAllowedRatherThanAnError() {
    UUID unknownOrganizationId = UUID.randomUUID();
    when(organizations.findById(unknownOrganizationId)).thenReturn(Optional.empty());

    assertThat(
            bridge.isProviderAllowed(
                new OrganizationId(unknownOrganizationId), SocialProvider.GOOGLE))
        .isFalse();
  }
}
