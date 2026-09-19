package com.clavaris.organization.application.usecase.listaccessrestrictionentriesfororganization;

import com.clavaris.organization.application.usecase.addaccessrestrictionentry.AccessRestrictionEntryRepository;
import com.clavaris.organization.domain.model.AccessRestrictionEntry;
import java.util.List;

/**
 * Orchestration for {@link ListAccessRestrictionEntriesForOrganizationUseCase}. No pagination — a
 * per-Organization blocklist/allowlist is an operator-curated list, not user-generated content;
 * real-world size is a handful to a few dozen entries, not the thousands {@code
 * ListOAuthClientsPagedUseCase}'s own keyset pagination exists for.
 */
public class ListAccessRestrictionEntriesForOrganizationService
    implements ListAccessRestrictionEntriesForOrganizationUseCase {

  private final AccessRestrictionEntryRepository entries;

  public ListAccessRestrictionEntriesForOrganizationService(
      final AccessRestrictionEntryRepository entries) {
    this.entries = entries;
  }

  @Override
  public List<AccessRestrictionEntry> handle(
      final ListAccessRestrictionEntriesForOrganizationQuery query) {
    return entries.findAllByOrganizationId(query.organizationId());
  }
}
