package com.clavaris.organization.application.usecase.addaccessrestrictionentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.organization.domain.model.AccessRestrictionEntry;
import com.clavaris.organization.domain.model.RestrictionType;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AddAccessRestrictionEntryServiceTest {

  private final UUID organizationId = UUID.randomUUID();

  private AccessRestrictionEntryRepository entries;
  private AddAccessRestrictionEntryService service;

  @BeforeEach
  void setUp() {
    entries = mock(AccessRestrictionEntryRepository.class);
    service = new AddAccessRestrictionEntryService(entries);
  }

  @Test
  void createsAndSavesANewEntry() {
    AddAccessRestrictionEntryCommand command =
        new AddAccessRestrictionEntryCommand(
            organizationId, RestrictionType.BLOCKLIST, "blocked@example.com");

    AccessRestrictionEntry entry = service.handle(command);

    assertThat(entry.organizationId()).isEqualTo(organizationId);
    assertThat(entry.type()).isEqualTo(RestrictionType.BLOCKLIST);
    assertThat(entry.identifier()).isEqualTo("blocked@example.com");
    verify(entries).saveAndFlush(entry);
  }

  @Test
  void normalizesTheIdentifierBeforeCheckingForADuplicate() {
    AddAccessRestrictionEntryCommand command =
        new AddAccessRestrictionEntryCommand(
            organizationId, RestrictionType.BLOCKLIST, "Blocked@Example.com");

    service.handle(command);

    verify(entries).existsByOrganizationIdAndIdentifier(organizationId, "blocked@example.com");
  }

  @Test
  void rejectsADuplicateIdentifierForTheSameOrganizationWithoutSaving() {
    when(entries.existsByOrganizationIdAndIdentifier(organizationId, "blocked@example.com"))
        .thenReturn(true);
    AddAccessRestrictionEntryCommand command =
        new AddAccessRestrictionEntryCommand(
            organizationId, RestrictionType.BLOCKLIST, "blocked@example.com");

    assertThatExceptionOfType(DuplicateAccessRestrictionEntryException.class)
        .isThrownBy(() -> service.handle(command));

    verify(entries, never()).save(any());
  }

  @Test
  void translatesADataIntegrityViolationFromALostRaceIntoTheTypedException() {
    // TD-SEC-060: the pre-check above passes (existsByOrganizationIdAndIdentifier defaults to
    // false), but ux_access_restriction_entries_organization_id_identifier still fires at
    // saveAndFlush time — simulating a concurrent request that added the same identifier first.
    doThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"))
        .when(entries)
        .saveAndFlush(any());
    AddAccessRestrictionEntryCommand command =
        new AddAccessRestrictionEntryCommand(
            organizationId, RestrictionType.BLOCKLIST, "blocked@example.com");

    assertThatExceptionOfType(DuplicateAccessRestrictionEntryException.class)
        .isThrownBy(() -> service.handle(command));
  }
}
