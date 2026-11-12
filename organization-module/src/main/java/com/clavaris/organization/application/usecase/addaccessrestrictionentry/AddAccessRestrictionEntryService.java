package com.clavaris.organization.application.usecase.addaccessrestrictionentry;

import com.clavaris.organization.domain.model.AccessRestrictionEntry;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/** Orchestration for {@link AddAccessRestrictionEntryUseCase}. */
public class AddAccessRestrictionEntryService implements AddAccessRestrictionEntryUseCase {

  /**
   * TD-SEC-087: entries per Organization. Registration evaluates the whole list, so this bounds
   * that cost; a soft cap, since two requests racing at the boundary may each add one (the unique
   * index still stops duplicates).
   */
  public static final int MAX_ENTRIES_PER_ORGANIZATION = 1_000;

  private final AccessRestrictionEntryRepository entries;

  public AddAccessRestrictionEntryService(final AccessRestrictionEntryRepository entries) {
    this.entries = entries;
  }

  // TD-SEC-094: one transaction, and the Organization's lock first. The duplicate check, the count
  // against the cap and the insert used to be three steps with nothing between them, so parallel
  // adds could each pass the cap and end above it; now a second add waits for the first to commit
  // and counts what it left.
  @Override
  @Transactional
  public AccessRestrictionEntry handle(final AddAccessRestrictionEntryCommand command) {
    entries.lockForChange(command.organizationId());
    final String normalizedIdentifier = command.identifier().trim().toLowerCase(Locale.ROOT);
    if (entries.existsByOrganizationIdAndIdentifier(
        command.organizationId(), normalizedIdentifier)) {
      throw new DuplicateAccessRestrictionEntryException(normalizedIdentifier);
    }
    if (entries.countByOrganizationId(command.organizationId()) >= MAX_ENTRIES_PER_ORGANIZATION) {
      throw new AccessRestrictionLimitExceededException(
          command.organizationId(), MAX_ENTRIES_PER_ORGANIZATION);
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
