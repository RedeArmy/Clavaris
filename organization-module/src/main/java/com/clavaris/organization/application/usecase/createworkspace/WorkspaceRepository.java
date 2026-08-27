package com.clavaris.organization.application.usecase.createworkspace;

import com.clavaris.organization.domain.model.Workspace;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaWorkspaceRepository}. Parked under {@code
 * createworkspace} because that's this module's first Workspace use case, not because {@code
 * findById}/{@code findAllByOrganizationId} are scoped to it — {@code addworkspacemember} and
 * {@code listworkspacesfororganization} are the other consumers, same precedent this module's own
 * {@code OrganizationRepository} already established.
 */
public interface WorkspaceRepository {

  void save(Workspace workspace);

  Optional<Workspace> findById(UUID workspaceId);

  List<Workspace> findAllByOrganizationId(UUID organizationId);
}
