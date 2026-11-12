package com.clavaris.organization.application.usecase.addaccessrestrictionentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.organization.domain.model.AccessRestrictionEntry;
import com.clavaris.organization.domain.model.RestrictionType;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

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

  // TD-SEC-094: the lock comes before the duplicate check and the count, or two adds could still
  // read the same count.
  @Test
  void takesTheOrganizationLockBeforeCheckingOrCountingAnything() {
    UUID organizationId = UUID.randomUUID();
    when(entries.existsByOrganizationIdAndIdentifier(any(), any())).thenReturn(false);
    when(entries.countByOrganizationId(organizationId)).thenReturn(0L);

    service.handle(
        new AddAccessRestrictionEntryCommand(
            organizationId, RestrictionType.BLOCKLIST, "first@example.com"));

    InOrder order = inOrder(entries);
    order.verify(entries).lockForChange(organizationId);
    order.verify(entries).existsByOrganizationIdAndIdentifier(organizationId, "first@example.com");
    order.verify(entries).countByOrganizationId(organizationId);
    order.verify(entries).saveAndFlush(any());
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

  // TD-SEC-087: registration evaluates the whole list, so an Organization may hold only so many.
  @Test
  void refusesAnEntryOnceTheOrganizationIsAtItsCap() {
    when(entries.countByOrganizationId(organizationId))
        .thenReturn((long) AddAccessRestrictionEntryService.MAX_ENTRIES_PER_ORGANIZATION);
    AddAccessRestrictionEntryCommand command =
        new AddAccessRestrictionEntryCommand(
            organizationId, RestrictionType.BLOCKLIST, "one-too-many@example.com");

    assertThatExceptionOfType(AccessRestrictionLimitExceededException.class)
        .isThrownBy(() -> service.handle(command));

    verify(entries, never()).saveAndFlush(any());
  }

  @Test
  void acceptsTheLastEntryThatFitsUnderTheCap() {
    when(entries.countByOrganizationId(organizationId))
        .thenReturn((long) AddAccessRestrictionEntryService.MAX_ENTRIES_PER_ORGANIZATION - 1);

    AccessRestrictionEntry entry =
        service.handle(
            new AddAccessRestrictionEntryCommand(
                organizationId, RestrictionType.BLOCKLIST, "the-last@example.com"));

    verify(entries).saveAndFlush(entry);
  }

  // A duplicate is reported as a duplicate even when the list is full: that is the truer answer.
  @Test
  void aDuplicateIsStillReportedAsADuplicateWhenTheListIsFull() {
    when(entries.existsByOrganizationIdAndIdentifier(organizationId, "blocked@example.com"))
        .thenReturn(true);
    when(entries.countByOrganizationId(organizationId))
        .thenReturn((long) AddAccessRestrictionEntryService.MAX_ENTRIES_PER_ORGANIZATION);
    AddAccessRestrictionEntryCommand command =
        new AddAccessRestrictionEntryCommand(
            organizationId, RestrictionType.BLOCKLIST, "blocked@example.com");

    assertThatExceptionOfType(DuplicateAccessRestrictionEntryException.class)
        .isThrownBy(() -> service.handle(command));
  }
}
