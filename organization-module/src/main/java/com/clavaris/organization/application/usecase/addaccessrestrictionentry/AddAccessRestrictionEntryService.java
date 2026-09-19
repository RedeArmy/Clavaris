package com.clavaris.organization.application.usecase.addaccessrestrictionentry;

import com.clavaris.organization.domain.model.AccessRestrictionEntry;
import java.util.Locale;

/** Orchestration for {@link AddAccessRestrictionEntryUseCase}. */
@SuppressWarnings("PMD.LongVariable")
public class AddAccessRestrictionEntryService implements AddAccessRestrictionEntryUseCase {

  private final AccessRestrictionEntryRepository entries;

  public AddAccessRestrictionEntryService(final AccessRestrictionEntryRepository entries) {
    this.entries = entries;
  }

  @Override
  public AccessRestrictionEntry handle(final AddAccessRestrictionEntryCommand command) {
    final String normalizedIdentifier = command.identifier().trim().toLowerCase(Locale.ROOT);
    if (entries.existsByOrganizationIdAndIdentifier(
        command.organizationId(), normalizedIdentifier)) {
      throw new DuplicateAccessRestrictionEntryException(normalizedIdentifier);
    }
    final AccessRestrictionEntry entry =
        AccessRestrictionEntry.create(
            command.organizationId(), command.type(), command.identifier());
    entries.save(entry);
    return entry;
  }
}
