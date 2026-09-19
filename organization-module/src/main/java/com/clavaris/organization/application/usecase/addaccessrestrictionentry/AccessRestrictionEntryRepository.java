package com.clavaris.organization.application.usecase.addaccessrestrictionentry;

import com.clavaris.organization.domain.model.AccessRestrictionEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaAccessRestrictionEntryRepository}. Lives in this
 * use-case folder (the first one that needed it) and is imported by the sibling remove/list/check
 * use cases — same "one port, several importing use cases" precedent {@code
 * registeroauthclient.OAuthClientRepository} already establishes in client-registry-module.
 */
@SuppressWarnings({"PMD.LongVariable", "PMD.ShortVariable"})
public interface AccessRestrictionEntryRepository {

  boolean existsByOrganizationIdAndIdentifier(UUID organizationId, String normalizedIdentifier);

  void save(AccessRestrictionEntry entry);

  Optional<AccessRestrictionEntry> findById(UUID id);

  void deleteById(UUID id);

  List<AccessRestrictionEntry> findAllByOrganizationId(UUID organizationId);
}
