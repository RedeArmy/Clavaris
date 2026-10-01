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

  /**
   * TD-SEC-060: same write as {@link #save}, but flushed immediately rather than deferred to the
   * enclosing transaction's own commit — {@code AddAccessRestrictionEntryService} needs the {@code
   * ux_access_restriction_entries_organization_id_identifier} constraint violation to surface
   * synchronously, catchable right where it's thrown, not at a commit boundary the calling method
   * has already returned past — same "a dedicated flushed write for the race-sensitive caller,
   * plain {@link #save} everywhere else" precedent {@code WorkspaceRoleRepository#saveAndFlush}
   * already establishes for an identical shape of race.
   */
  void saveAndFlush(AccessRestrictionEntry entry);

  Optional<AccessRestrictionEntry> findById(UUID id);

  void deleteById(UUID id);

  List<AccessRestrictionEntry> findAllByOrganizationId(UUID organizationId);
}
