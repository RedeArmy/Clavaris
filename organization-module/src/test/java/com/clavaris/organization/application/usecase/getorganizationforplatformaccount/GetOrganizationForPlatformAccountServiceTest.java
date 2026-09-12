package com.clavaris.organization.application.usecase.getorganizationforplatformaccount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.Organization;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GetOrganizationForPlatformAccountServiceTest {

  private final OrganizationRepository organizations = mock(OrganizationRepository.class);
  private final GetOrganizationForPlatformAccountService service =
      new GetOrganizationForPlatformAccountService(organizations);

  @Test
  void resolvesAnOrganizationOwnedByTheRequestingPlatformAccount() {
    UUID ownerId = UUID.randomUUID();
    Organization organization = Organization.register("Acme Co", ownerId);
    when(organizations.findById(organization.id())).thenReturn(Optional.of(organization));

    Optional<Organization> result =
        service.handle(new GetOrganizationForPlatformAccountQuery(organization.id(), ownerId));

    assertThat(result).contains(organization);
  }

  @Test
  void resolvesEmptyForAnUnknownOrganizationId() {
    UUID organizationId = UUID.randomUUID();
    when(organizations.findById(organizationId)).thenReturn(Optional.empty());

    Optional<Organization> result =
        service.handle(
            new GetOrganizationForPlatformAccountQuery(organizationId, UUID.randomUUID()));

    assertThat(result).isEmpty();
  }

  // Cross-tenant defense in depth: an Organization owned by a DIFFERENT PlatformAccount must
  // resolve identically to "doesn't exist" — never a distinguishable outcome a caller could use
  // to enumerate real Organization ids by probing this query with someone else's id.
  @Test
  void resolvesEmptyForAnOrganizationOwnedByADifferentPlatformAccount() {
    Organization organization = Organization.register("Someone Else's Co", UUID.randomUUID());
    when(organizations.findById(organization.id())).thenReturn(Optional.of(organization));

    Optional<Organization> result =
        service.handle(
            new GetOrganizationForPlatformAccountQuery(organization.id(), UUID.randomUUID()));

    assertThat(result).isEmpty();
  }
}
