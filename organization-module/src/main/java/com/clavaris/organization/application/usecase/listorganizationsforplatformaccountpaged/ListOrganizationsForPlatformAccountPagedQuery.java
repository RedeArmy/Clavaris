package com.clavaris.organization.application.usecase.listorganizationsforplatformaccountpaged;

import com.clavaris.common.domain.model.KeysetPageRequest;
import java.util.UUID;

@SuppressWarnings("PMD.LongVariable")
public record ListOrganizationsForPlatformAccountPagedQuery(
    UUID ownerPlatformAccountId, KeysetPageRequest pageRequest) {}
