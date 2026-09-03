package com.clavaris.organization.application.usecase.addworkspacemember;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * SDE-III review, 2026-09-03: {@link LastAdminGuard} is the single, shared, lock-then-count
 * implementation both {@code RemoveWorkspaceMemberService} and {@code
 * ChangeWorkspaceMemberRoleService} now delegate to — see its own Javadoc for the TOCTOU race and
 * verbatim-duplication it replaced.
 */
class LastAdminGuardTest {

  private final UUID workspaceId = UUID.randomUUID();
  private final WorkspaceMembershipRepository memberships =
      mock(WorkspaceMembershipRepository.class);

  @Test
  void locksBeforeCounting() {
    when(memberships.countByWorkspaceIdAndRole(workspaceId, WorkspaceRole.ADMIN)).thenReturn(2L);

    LastAdminGuard.assertAtLeastOneAdminWouldRemain(
        memberships, workspaceId, IllegalStateException::new);

    InOrder inOrder = Mockito.inOrder(memberships);
    inOrder.verify(memberships).lockForRoleChange(workspaceId);
    inOrder.verify(memberships).countByWorkspaceIdAndRole(eq(workspaceId), eq(WorkspaceRole.ADMIN));
  }

  @Test
  void doesNotThrowWhenMoreThanOneAdminRemains() {
    when(memberships.countByWorkspaceIdAndRole(workspaceId, WorkspaceRole.ADMIN)).thenReturn(2L);

    LastAdminGuard.assertAtLeastOneAdminWouldRemain(
        memberships, workspaceId, IllegalStateException::new);
  }

  @Test
  void throwsTheCallerSuppliedExceptionWhenOnlyOneAdminRemains() {
    when(memberships.countByWorkspaceIdAndRole(workspaceId, WorkspaceRole.ADMIN)).thenReturn(1L);

    assertThatExceptionOfType(CannotRemoveLastAdminExceptionForTest.class)
        .isThrownBy(
            () ->
                LastAdminGuard.assertAtLeastOneAdminWouldRemain(
                    memberships, workspaceId, CannotRemoveLastAdminExceptionForTest::new));
  }

  /** A caller-supplied exception type stand-in, proving the guard throws whatever it's given. */
  private static final class CannotRemoveLastAdminExceptionForTest extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
