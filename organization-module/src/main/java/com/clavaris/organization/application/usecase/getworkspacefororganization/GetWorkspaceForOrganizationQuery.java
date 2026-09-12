package com.clavaris.organization.application.usecase.getworkspacefororganization;

import java.util.UUID;

public record GetWorkspaceForOrganizationQuery(UUID organizationId, UUID workspaceId) {}
