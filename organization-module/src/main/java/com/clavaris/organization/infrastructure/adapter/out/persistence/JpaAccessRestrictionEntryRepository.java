package com.clavaris.organization.infrastructure.adapter.out.persistence;

import com.clavaris.organization.application.usecase.addaccessrestrictionentry.AccessRestrictionEntryRepository;
import com.clavaris.organization.domain.model.AccessRestrictionEntry;
import com.clavaris.organization.domain.model.RestrictionType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Implements the outbound port. */
@SuppressWarnings({"PMD.LongVariable", "PMD.ShortVariable"})
@Repository
class JpaAccessRestrictionEntryRepository implements AccessRestrictionEntryRepository {

  private final SpringDataAccessRestrictionEntryJpaRepository entries;

  /* package */ JpaAccessRestrictionEntryRepository(
      final SpringDataAccessRestrictionEntryJpaRepository entries) {
    this.entries = entries;
  }

  @Override
  public boolean existsByOrganizationIdAndIdentifier(
      final UUID organizationId, final String normalizedIdentifier) {
    return entries.existsByOrganizationIdAndIdentifier(organizationId, normalizedIdentifier);
  }

  @Override
  public void save(final AccessRestrictionEntry entry) {
    entries.save(
        new AccessRestrictionEntryEntity(
            entry.id(),
            entry.organizationId(),
            entry.type().name(),
            entry.identifier(),
            entry.createdAt()));
  }

  @Override
  public Optional<AccessRestrictionEntry> findById(final UUID id) {
    return entries.findById(id).map(this::toDomain);
  }

  @Override
  public void deleteById(final UUID id) {
    entries.deleteById(id);
  }

  @Override
  public List<AccessRestrictionEntry> findAllByOrganizationId(final UUID organizationId) {
    return entries.findAllByOrganizationId(organizationId).stream().map(this::toDomain).toList();
  }

  private AccessRestrictionEntry toDomain(final AccessRestrictionEntryEntity entity) {
    return AccessRestrictionEntry.reconstitute(
        entity.getId(),
        entity.getOrganizationId(),
        RestrictionType.valueOf(entity.getType()),
        entity.getIdentifier(),
        entity.getCreatedAt());
  }
}
