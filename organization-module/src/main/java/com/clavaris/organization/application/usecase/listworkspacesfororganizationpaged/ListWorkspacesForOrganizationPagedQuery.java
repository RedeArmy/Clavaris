package com.clavaris.organization.application.usecase.listworkspacesfororganizationpaged;

import com.clavaris.common.domain.model.PageRequest;
import java.util.UUID;

public record ListWorkspacesForOrganizationPagedQuery(
    UUID organizationId, PageRequest pageRequest) {}
