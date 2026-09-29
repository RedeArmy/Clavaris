package com.clavaris.organization.application.usecase.deleteworkspace;

/**
 * Hard-deletes a {@code Workspace} and, via DB cascade, every {@code WorkspaceMembership} and
 * {@code WorkspaceTeam} it owns — see {@link DeleteWorkspaceService}'s own Javadoc for exactly what
 * is and is not erased.
 */
@FunctionalInterface
public interface DeleteWorkspaceUseCase {

  void handle(DeleteWorkspaceCommand command);
}
