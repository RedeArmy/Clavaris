package com.clavaris.organization.infrastructure.adapter.out.persistence;

import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * Implements the outbound port; maps between {@code domain.model.WorkspaceRole} and {@link
 * WorkspaceRoleEntity} — {@code permissions} (de)serialized via {@link ObjectMapper}, same split
 * responsibility {@code JpaWebhookEndpointRepository}'s own identical {@code subscribedEventTypes}
 * handling already establishes.
 */
@Repository
class JpaWorkspaceRoleRepository implements WorkspaceRoleRepository {

  private final SpringDataWorkspaceRoleJpaRepository roles;
  private final ObjectMapper objectMapper;

  /* package */ JpaWorkspaceRoleRepository(
      final SpringDataWorkspaceRoleJpaRepository roles, final ObjectMapper objectMapper) {
    this.roles = roles;
    this.objectMapper = objectMapper;
  }

  @Override
  public void save(final WorkspaceRole role) {
    roles.save(
        new WorkspaceRoleEntity(
            role.id(),
            role.organizationId(),
            role.name(),
            role.parentRoleId(),
            objectMapper.writeValueAsString(role.permissions()),
            role.reserved(),
            role.createdAt()));
  }

  @Override
  public Optional<WorkspaceRole> findById(final UUID roleId) {
    return roles.findById(roleId).map(this::toDomain);
  }

  @Override
  public List<WorkspaceRole> findAllByOrganizationId(final UUID organizationId) {
    return roles.findAllByOrganizationId(organizationId).stream().map(this::toDomain).toList();
  }

  private WorkspaceRole toDomain(final WorkspaceRoleEntity entity) {
    final Set<String> permissions =
        Set.of(objectMapper.readValue(entity.getPermissions(), String[].class));
    return WorkspaceRole.reconstitute(
        entity.getId(),
        entity.getOrganizationId(),
        entity.getName(),
        entity.getParentRoleId(),
        permissions,
        entity.isReserved(),
        entity.getCreatedAt());
  }
}
