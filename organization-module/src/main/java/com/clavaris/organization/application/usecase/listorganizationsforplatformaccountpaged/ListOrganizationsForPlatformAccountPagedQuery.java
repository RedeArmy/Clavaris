package com.clavaris.organization.application.usecase.listorganizationsforplatformaccountpaged;

import com.clavaris.common.domain.model.KeysetPageRequest;
import java.util.UUID;

public record ListOrganizationsForPlatformAccountPagedQuery(
    UUID ownerPlatformAccountId, KeysetPageRequest pageRequest) {}
