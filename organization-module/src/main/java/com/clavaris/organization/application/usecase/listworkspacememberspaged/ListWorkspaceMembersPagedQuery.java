package com.clavaris.organization.application.usecase.listworkspacememberspaged;

import com.clavaris.common.domain.model.KeysetPageRequest;
import java.util.UUID;

public record ListWorkspaceMembersPagedQuery(UUID workspaceId, KeysetPageRequest pageRequest) {}
