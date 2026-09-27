package com.clavaris.organization.application.usecase.listworkspacerolesfororganization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ListWorkspaceRolesForOrganizationServiceTest {

  @Test
  void returnsEveryRoleTheRepositoryHasForThatOrganization() {
    UUID organizationId = UUID.randomUUID();
    WorkspaceRole role = WorkspaceRole.define(organizationId, "Supervisor", null, Set.of("a"));
    WorkspaceRoleRepository roles = mock(WorkspaceRoleRepository.class);
    when(roles.findAllByOrganizationId(organizationId)).thenReturn(List.of(role));
    ListWorkspaceRolesForOrganizationService service =
        new ListWorkspaceRolesForOrganizationService(roles);

    List<WorkspaceRole> found =
        service.handle(new ListWorkspaceRolesForOrganizationQuery(organizationId));

    assertThat(found).containsExactly(role);
  }

  @Test
  void returnsAnEmptyListWhenTheOrganizationHasNoRolesYet() {
    UUID organizationId = UUID.randomUUID();
    WorkspaceRoleRepository roles = mock(WorkspaceRoleRepository.class);
    when(roles.findAllByOrganizationId(organizationId)).thenReturn(List.of());
    ListWorkspaceRolesForOrganizationService service =
        new ListWorkspaceRolesForOrganizationService(roles);

    List<WorkspaceRole> found =
        service.handle(new ListWorkspaceRolesForOrganizationQuery(organizationId));

    assertThat(found).isEmpty();
  }
}
