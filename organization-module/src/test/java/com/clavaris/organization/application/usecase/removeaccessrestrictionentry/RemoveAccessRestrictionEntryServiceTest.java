package com.clavaris.organization.application.usecase.removeaccessrestrictionentry;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.organization.application.usecase.addaccessrestrictionentry.AccessRestrictionEntryRepository;
import com.clavaris.organization.domain.model.AccessRestrictionEntry;
import com.clavaris.organization.domain.model.RestrictionType;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RemoveAccessRestrictionEntryServiceTest {

  private final UUID organizationId = UUID.randomUUID();

  private AccessRestrictionEntryRepository entries;
  private RemoveAccessRestrictionEntryService service;

  @BeforeEach
  void setUp() {
    entries = mock(AccessRestrictionEntryRepository.class);
    service = new RemoveAccessRestrictionEntryService(entries);
  }

  private AccessRestrictionEntry sampleEntry() {
    return AccessRestrictionEntry.create(
        organizationId, RestrictionType.BLOCKLIST, "blocked@example.com");
  }

  @Test
  void deletesAnEntryOwnedByTheOrganization() {
    AccessRestrictionEntry entry = sampleEntry();
    when(entries.findById(entry.id())).thenReturn(Optional.of(entry));

    service.handle(new RemoveAccessRestrictionEntryCommand(organizationId, entry.id()));

    verify(entries).deleteById(entry.id());
  }

  @Test
  void throwsWhenTheEntryDoesNotExist() {
    UUID entryId = UUID.randomUUID();
    when(entries.findById(entryId)).thenReturn(Optional.empty());
    RemoveAccessRestrictionEntryCommand command =
        new RemoveAccessRestrictionEntryCommand(organizationId, entryId);

    assertThatExceptionOfType(AccessRestrictionEntryNotFoundException.class)
        .isThrownBy(() -> service.handle(command));
    verify(entries, never()).deleteById(any());
  }

  // BR-ORG-02-shaped isolation: an entry belonging to a DIFFERENT Organization must resolve as
  // "not found" here, never actually deleted — same anti-cross-tenant posture as every other
  // "resolve by id, then verify organizationId" removal in this codebase.
  @Test
  void treatsAnEntryBelongingToADifferentOrganizationAsNotFound() {
    AccessRestrictionEntry entry = sampleEntry();
    when(entries.findById(entry.id())).thenReturn(Optional.of(entry));
    RemoveAccessRestrictionEntryCommand command =
        new RemoveAccessRestrictionEntryCommand(UUID.randomUUID(), entry.id());

    assertThatExceptionOfType(AccessRestrictionEntryNotFoundException.class)
        .isThrownBy(() -> service.handle(command));
    verify(entries, never()).deleteById(any());
  }
}
