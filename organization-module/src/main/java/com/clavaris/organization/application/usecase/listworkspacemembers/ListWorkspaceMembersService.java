package com.clavaris.organization.application.usecase.listworkspacemembers;

import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import java.util.List;

public class ListWorkspaceMembersService implements ListWorkspaceMembersUseCase {

  private final WorkspaceMembershipRepository memberships;

  public ListWorkspaceMembersService(final WorkspaceMembershipRepository memberships) {
    this.memberships = memberships;
  }

  @Override
  public List<WorkspaceMembership> handle(final ListWorkspaceMembersQuery query) {
    return memberships.findAllByWorkspaceId(query.workspaceId());
  }
}
