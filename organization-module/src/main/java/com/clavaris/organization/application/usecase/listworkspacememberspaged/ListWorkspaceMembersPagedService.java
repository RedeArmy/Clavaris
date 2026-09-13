package com.clavaris.organization.application.usecase.listworkspacememberspaged;

import com.clavaris.common.domain.model.Page;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.domain.model.WorkspaceMembership;

public class ListWorkspaceMembersPagedService implements ListWorkspaceMembersPagedUseCase {

  private final WorkspaceMembershipRepository memberships;

  public ListWorkspaceMembersPagedService(final WorkspaceMembershipRepository memberships) {
    this.memberships = memberships;
  }

  @Override
  public Page<WorkspaceMembership> handle(final ListWorkspaceMembersPagedQuery query) {
    return memberships.findPageByWorkspaceId(query.workspaceId(), query.pageRequest());
  }
}
