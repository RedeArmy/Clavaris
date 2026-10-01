package com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace;

import java.util.Collection;
import java.util.UUID;

public record ListWorkspaceTeamRoleIdsForTeamsQuery(Collection<UUID> teamIds) {}
