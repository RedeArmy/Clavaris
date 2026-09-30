package com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TD-PERF-030: the batched sibling of {@link ListWorkspaceTeamRoleIdsUseCase} — every team's own
 * grouped roleIds in one call, for a caller (both {@code PlatformWorkspaceController#
 * loadTeamsAndRoles} and {@code PlatformAccountWorkspaceRoleController#populateRoleOptions}) that
 * previously called that single-team use case once per team in a loop.
 */
@FunctionalInterface
public interface ListWorkspaceTeamRoleIdsForTeamsUseCase {

  Map<UUID, List<UUID>> handle(ListWorkspaceTeamRoleIdsForTeamsQuery query);
}
