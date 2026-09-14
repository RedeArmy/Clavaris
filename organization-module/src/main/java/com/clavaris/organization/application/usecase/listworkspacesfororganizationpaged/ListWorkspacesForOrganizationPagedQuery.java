package com.clavaris.organization.application.usecase.listworkspacesfororganizationpaged;

import com.clavaris.common.domain.model.KeysetPageRequest;
import java.util.UUID;

public record ListWorkspacesForOrganizationPagedQuery(
    UUID organizationId, KeysetPageRequest pageRequest) {}
