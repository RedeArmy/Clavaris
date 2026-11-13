package com.clavaris.organization.application.usecase.addworkspacemember;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.clavaris.organization.domain.model.WorkspaceMembership;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Makes a mocked {@link WorkspaceMembershipRepository} answer the three reads the role-change
 * guards use (TD-PERF-059/062) from one list of memberships, so a test states the Workspace's
 * members once and the count, the by-role lookup and the full listing all agree with it.
 */
public final class WorkspaceMembershipStubs {

  private WorkspaceMembershipStubs() {
    // Static helper.
  }

  public static void stubMemberships(
      final WorkspaceMembershipRepository repository,
      final UUID workspaceId,
      final List<WorkspaceMembership> members) {
    when(repository.findAllByWorkspaceId(workspaceId)).thenReturn(members);
    when(repository.countByWorkspaceIdAndRoleIdIn(eq(workspaceId), anyCollection()))
        .thenAnswer(
            invocation -> {
              final Collection<UUID> roleIds = invocation.getArgument(1);
              return members.stream().filter(m -> roleIds.contains(m.roleId())).count();
            });
    when(repository.findAllByWorkspaceIdAndRoleId(
            eq(workspaceId), org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              final UUID roleId = invocation.getArgument(1);
              return members.stream().filter(m -> roleId.equals(m.roleId())).toList();
            });
  }
}
