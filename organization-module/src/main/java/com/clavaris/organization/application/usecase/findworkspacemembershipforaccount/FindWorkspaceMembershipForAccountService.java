package com.clavaris.organization.application.usecase.findworkspacemembershipforaccount;

import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import java.util.Optional;

public class FindWorkspaceMembershipForAccountService
    implements FindWorkspaceMembershipForAccountUseCase {

  private final WorkspaceMembershipRepository memberships;

  public FindWorkspaceMembershipForAccountService(final WorkspaceMembershipRepository memberships) {
    this.memberships = memberships;
  }

  @Override
  public Optional<WorkspaceMembership> handle(final FindWorkspaceMembershipForAccountQuery query) {
    return memberships.findAllByAccountId(query.accountId()).stream().findFirst();
  }
}
