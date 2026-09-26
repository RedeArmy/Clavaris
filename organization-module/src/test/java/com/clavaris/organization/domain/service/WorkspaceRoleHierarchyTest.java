package com.clavaris.organization.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class WorkspaceRoleHierarchyTest {

  private final UUID organizationId = UUID.randomUUID();

  private Map<UUID, WorkspaceRole> index(final WorkspaceRole... roles) {
    return java.util.Arrays.stream(roles)
        .collect(Collectors.toMap(WorkspaceRole::id, Function.identity()));
  }

  @Test
  void effectivePermissionsOfARoleWithNoParentIsJustItsOwn() {
    WorkspaceRole role = WorkspaceRole.define(organizationId, "Agent", null, Set.of("a", "b"));

    Set<String> effective = WorkspaceRoleHierarchy.effectivePermissions(role.id(), index(role));

    assertThat(effective).containsExactlyInAnyOrder("a", "b");
  }

  @Test
  void effectivePermissionsUnionsTheWholeParentChain() {
    WorkspaceRole grandparent =
        WorkspaceRole.define(organizationId, "Agent", null, Set.of("agent-perm"));
    WorkspaceRole parent =
        WorkspaceRole.define(
            organizationId, "Supervisor", grandparent.id(), Set.of("supervisor-perm"));
    WorkspaceRole child =
        WorkspaceRole.define(organizationId, "Manager", parent.id(), Set.of("manager-perm"));

    Set<String> effective =
        WorkspaceRoleHierarchy.effectivePermissions(child.id(), index(grandparent, parent, child));

    assertThat(effective)
        .containsExactlyInAnyOrder("agent-perm", "supervisor-perm", "manager-perm");
  }

  @Test
  void effectivePermissionsStopsRatherThanLoopingForeverOnAnAlreadyCorruptCycle() {
    UUID roleAId = UUID.randomUUID();
    UUID roleBId = UUID.randomUUID();
    WorkspaceRole roleA =
        WorkspaceRole.reconstitute(
            roleAId, organizationId, "A", roleBId, Set.of("a"), false, java.time.Instant.now());
    WorkspaceRole roleB =
        WorkspaceRole.reconstitute(
            roleBId, organizationId, "B", roleAId, Set.of("b"), false, java.time.Instant.now());

    Set<String> effective =
        WorkspaceRoleHierarchy.effectivePermissions(roleAId, index(roleA, roleB));

    assertThat(effective).containsExactlyInAnyOrder("a", "b");
  }

  @Test
  void effectivePermissionsOfAnUnknownRoleIsEmpty() {
    Set<String> effective =
        WorkspaceRoleHierarchy.effectivePermissions(UUID.randomUUID(), Map.of());

    assertThat(effective).isEmpty();
  }

  @Test
  void wouldCreateCycleIsFalseWhenTheCandidateParentHasNoAncestors() {
    WorkspaceRole role = WorkspaceRole.define(organizationId, "Agent", null, Set.of());
    WorkspaceRole candidateParent =
        WorkspaceRole.define(organizationId, "Supervisor", null, Set.of());

    boolean wouldCycle =
        WorkspaceRoleHierarchy.wouldCreateCycle(
            role.id(), candidateParent.id(), index(role, candidateParent));

    assertThat(wouldCycle).isFalse();
  }

  @Test
  void wouldCreateCycleIsTrueWhenTheCandidateParentIsTheRoleItself() {
    WorkspaceRole role = WorkspaceRole.define(organizationId, "Agent", null, Set.of());

    boolean wouldCycle = WorkspaceRoleHierarchy.wouldCreateCycle(role.id(), role.id(), index(role));

    assertThat(wouldCycle).isTrue();
  }

  @Test
  void wouldCreateCycleIsTrueWhenTheCandidateParentsAncestorChainReachesTheRoleAgain() {
    WorkspaceRole role = WorkspaceRole.define(organizationId, "Agent", null, Set.of());
    WorkspaceRole middle = WorkspaceRole.define(organizationId, "Supervisor", role.id(), Set.of());

    // Setting role's parent to middle would create Agent -> Supervisor -> Agent.
    boolean wouldCycle =
        WorkspaceRoleHierarchy.wouldCreateCycle(role.id(), middle.id(), index(role, middle));

    assertThat(wouldCycle).isTrue();
  }
}
