package com.clavaris.organization.application.usecase.getworkspacefororganization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.domain.model.Workspace;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GetWorkspaceForOrganizationServiceTest {

  private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
  private final GetWorkspaceForOrganizationService service =
      new GetWorkspaceForOrganizationService(workspaces);

  @Test
  void resolvesAWorkspaceBelongingToTheGivenOrganization() {
    UUID organizationId = UUID.randomUUID();
    Workspace workspace = Workspace.register(organizationId, "Engineering");
    when(workspaces.findById(workspace.id())).thenReturn(Optional.of(workspace));

    Optional<Workspace> result =
        service.handle(new GetWorkspaceForOrganizationQuery(organizationId, workspace.id()));

    assertThat(result).contains(workspace);
  }

  @Test
  void resolvesEmptyForAnUnknownWorkspaceId() {
    UUID workspaceId = UUID.randomUUID();
    when(workspaces.findById(workspaceId)).thenReturn(Optional.empty());

    Optional<Workspace> result =
        service.handle(new GetWorkspaceForOrganizationQuery(UUID.randomUUID(), workspaceId));

    assertThat(result).isEmpty();
  }

  // Same anti-enumeration posture as GetOrganizationForPlatformAccountServiceTest's own identical
  // case: a Workspace that exists but belongs to a DIFFERENT Organization must resolve identically
  // to "doesn't exist" — the URL's own organizationId is never trusted as an implicit grant.
  @Test
  void resolvesEmptyForAWorkspaceBelongingToADifferentOrganization() {
    Workspace workspace = Workspace.register(UUID.randomUUID(), "Someone Else's Workspace");
    when(workspaces.findById(workspace.id())).thenReturn(Optional.of(workspace));

    Optional<Workspace> result =
        service.handle(new GetWorkspaceForOrganizationQuery(UUID.randomUUID(), workspace.id()));

    assertThat(result).isEmpty();
  }
}
