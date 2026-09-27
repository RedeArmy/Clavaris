package com.clavaris.identity.application.usecase.listaccountsfororganization;

import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.identity.domain.model.OrganizationId;

/**
 * @param searchTerm ADR-0029 — see {@code AccountRepository#findKeysetPageByOrganizationId}'s own
 *     Javadoc for the matching rule; {@code null} applies no filter.
 */
public record ListAccountsForOrganizationQuery(
    OrganizationId organizationId, KeysetPageRequest pageRequest, String searchTerm) {}
