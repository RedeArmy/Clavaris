package com.clavaris.organization.application.usecase.getorganizationforplatformaccount;

import com.clavaris.organization.domain.model.Organization;
import java.util.Optional;

/**
 * The dashboard's own Organization-detail read (ADR-0025) — {@code GET
 * /platform/dashboard/organizations/{id}}. See {@link GetOrganizationForPlatformAccountService}'s
 * own Javadoc for why this returns an empty {@link Optional} both when {@code organizationId}
 * doesn't resolve at all and when it resolves to an Organization owned by a different {@code
 * PlatformAccount}.
 */
@FunctionalInterface
public interface GetOrganizationForPlatformAccountUseCase {

  Optional<Organization> handle(GetOrganizationForPlatformAccountQuery query);
}
