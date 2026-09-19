package com.clavaris.organization.application.usecase.removeaccessrestrictionentry;

import com.clavaris.organization.application.usecase.addaccessrestrictionentry.AccessRestrictionEntryRepository;
import com.clavaris.organization.domain.model.AccessRestrictionEntry;

/** Orchestration for {@link RemoveAccessRestrictionEntryUseCase}. */
public class RemoveAccessRestrictionEntryService implements RemoveAccessRestrictionEntryUseCase {

  private final AccessRestrictionEntryRepository entries;

  public RemoveAccessRestrictionEntryService(final AccessRestrictionEntryRepository entries) {
    this.entries = entries;
  }

  @Override
  public void handle(final RemoveAccessRestrictionEntryCommand command) {
    final AccessRestrictionEntry entry =
        entries
            .findById(command.entryId())
            .filter(candidate -> candidate.organizationId().equals(command.organizationId()))
            .orElseThrow(() -> new AccessRestrictionEntryNotFoundException(command.entryId()));
    entries.deleteById(entry.id());
  }
}
