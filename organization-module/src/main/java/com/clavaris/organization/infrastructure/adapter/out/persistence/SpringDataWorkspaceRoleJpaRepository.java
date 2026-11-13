package com.clavaris.organization.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataWorkspaceRoleJpaRepository extends JpaRepository<WorkspaceRoleEntity, UUID> {

  List<WorkspaceRoleEntity> findAllByOrganizationId(UUID organizationId);

  // TD-PERF-058: the role and every ancestor above it, in one statement. UNION (not UNION ALL)
  // drops a row already reached, so a cycle that slipped past the write-time check ends the walk
  // instead of looping; the chain is as deep as the hierarchy is, normally one or two rows.
  @Query(
      value =
          """
          WITH RECURSIVE chain AS (
              SELECT * FROM workspace_roles WHERE id = :roleId
              UNION
              SELECT parent.* FROM workspace_roles parent
                JOIN chain child ON parent.id = child.parent_role_id
          )
          SELECT * FROM chain
          """,
      nativeQuery = true)
  List<WorkspaceRoleEntity> findWithAncestors(@Param("roleId") UUID roleId);
}
