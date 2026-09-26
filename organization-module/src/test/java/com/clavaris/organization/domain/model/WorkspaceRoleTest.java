package com.clavaris.organization.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkspaceRoleTest {

  private final UUID organizationId = UUID.randomUUID();

  @Test
  void defineAssignsARandomIdAndCapturesTheGivenFields() {
    UUID parentRoleId = UUID.randomUUID();
    Set<String> permissions = Set.of("org:posts:create");

    WorkspaceRole role =
        WorkspaceRole.define(organizationId, "Supervisor", parentRoleId, permissions);

    assertThat(role.id()).isNotNull();
    assertThat(role.organizationId()).isEqualTo(organizationId);
    assertThat(role.name()).isEqualTo("Supervisor");
    assertThat(role.parentRoleId()).isEqualTo(parentRoleId);
    assertThat(role.permissions()).isEqualTo(permissions);
    assertThat(role.reserved()).isFalse();
    assertThat(role.createdAt()).isNotNull();
  }

  @Test
  void defineReservedCarriesEveryReservedPermission() {
    WorkspaceRole role = WorkspaceRole.defineReserved(organizationId, "Admin");

    assertThat(role.reserved()).isTrue();
    assertThat(role.parentRoleId()).isNull();
    assertThat(role.permissions()).isEqualTo(ReservedWorkspacePermissions.ALL);
  }

  @Test
  void rejectsABlankName() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> WorkspaceRole.define(organizationId, " ", null, Set.of()));
  }

  @Test
  void rejectsSelfAsParent() {
    UUID id = UUID.randomUUID();
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                WorkspaceRole.reconstitute(
                    id, organizationId, "Circular", id, Set.of(), false, java.time.Instant.now()));
  }

  @Test
  void withNameKeepsEverythingElseUnchanged() {
    WorkspaceRole role = WorkspaceRole.define(organizationId, "Old Name", null, Set.of());

    WorkspaceRole renamed = role.withName("New Name");

    assertThat(renamed.id()).isEqualTo(role.id());
    assertThat(renamed.name()).isEqualTo("New Name");
    assertThat(renamed.permissions()).isEqualTo(role.permissions());
  }

  @Test
  void withPermissionsRejectsStrippingReservedPermissionsFromAReservedRole() {
    WorkspaceRole reserved = WorkspaceRole.defineReserved(organizationId, "Admin");

    assertThatIllegalArgumentException()
        .isThrownBy(() -> reserved.withPermissions(Set.of("something-else")));
  }

  @Test
  void withPermissionsAllowsAReservedRoleToKeepAllReservedPermissionsPlusMore() {
    WorkspaceRole reserved = WorkspaceRole.defineReserved(organizationId, "Admin");

    WorkspaceRole updated =
        reserved.withPermissions(
            Set.of(
                ReservedWorkspacePermissions.MANAGE_MEMBERS,
                ReservedWorkspacePermissions.MANAGE_ROLES,
                "org:extra:permission"));

    assertThat(updated.permissions()).contains("org:extra:permission");
  }

  @Test
  void withParentRoleIdRejectsSelfParenting() {
    WorkspaceRole role = WorkspaceRole.define(organizationId, "Role", null, Set.of());

    assertThatIllegalArgumentException().isThrownBy(() -> role.withParentRoleId(role.id()));
  }
}
