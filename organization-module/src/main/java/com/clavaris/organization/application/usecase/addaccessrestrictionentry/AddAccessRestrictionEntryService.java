package com.clavaris.organization.application.usecase.addaccessrestrictionentry;

import com.clavaris.organization.domain.model.AccessRestrictionEntry;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;

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
    try {
      // TD-SEC-060: saveAndFlush, not save — see CreateWorkspaceRoleService's own identical fix
      // for why the ux_access_restriction_entries_organization_id_identifier violation must
      // surface synchronously here.
      entries.saveAndFlush(entry);
    } catch (final DataIntegrityViolationException raceLost) {
      throw new DuplicateAccessRestrictionEntryException(normalizedIdentifier, raceLost);
    }
    return entry;
  }
}
