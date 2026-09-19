package com.clavaris.identity.application.usecase.listaccountsfororganization;

import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.identity.domain.model.OrganizationId;

public record ListAccountsForOrganizationQuery(
    OrganizationId organizationId, KeysetPageRequest pageRequest) {}
