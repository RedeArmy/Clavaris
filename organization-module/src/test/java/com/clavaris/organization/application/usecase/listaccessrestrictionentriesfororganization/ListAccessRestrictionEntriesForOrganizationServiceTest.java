package com.clavaris.organization.application.usecase.listaccessrestrictionentriesfororganization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.organization.application.usecase.addaccessrestrictionentry.AccessRestrictionEntryRepository;
import com.clavaris.organization.domain.model.AccessRestrictionEntry;
import com.clavaris.organization.domain.model.RestrictionType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ListAccessRestrictionEntriesForOrganizationServiceTest {

  @Test
  void delegatesToTheRepositoryForTheGivenOrganization() {
    UUID organizationId = UUID.randomUUID();
    AccessRestrictionEntryRepository entries = mock(AccessRestrictionEntryRepository.class);
    AccessRestrictionEntry entry =
        AccessRestrictionEntry.create(organizationId, RestrictionType.ALLOWLIST, "@example.com");
    when(entries.findAllByOrganizationId(organizationId)).thenReturn(List.of(entry));
    ListAccessRestrictionEntriesForOrganizationService service =
        new ListAccessRestrictionEntriesForOrganizationService(entries);

    List<AccessRestrictionEntry> result =
        service.handle(new ListAccessRestrictionEntriesForOrganizationQuery(organizationId));

    assertThat(result).containsExactly(entry);
  }
}
