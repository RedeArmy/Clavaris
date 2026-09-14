package com.clavaris.organization.application.usecase.listworkspacememberspaged;

import com.clavaris.common.domain.model.PageRequest;
import java.util.UUID;

public record ListWorkspaceMembersPagedQuery(UUID workspaceId, PageRequest pageRequest) {}
