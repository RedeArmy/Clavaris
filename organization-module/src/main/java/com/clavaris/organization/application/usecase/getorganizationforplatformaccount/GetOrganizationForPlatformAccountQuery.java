package com.clavaris.organization.application.usecase.getorganizationforplatformaccount;

import java.util.UUID;

/**
 * @param organizationId caller-supplied (a URL path segment) — never trusted alone, see {@link
 *     GetOrganizationForPlatformAccountService}'s own Javadoc for the ownership check this query's
 *     own {@code ownerPlatformAccountId} exists to enforce.
 * @param ownerPlatformAccountId the current session's own resolved principal, never caller input.
 */
@SuppressWarnings("PMD.LongVariable")
public record GetOrganizationForPlatformAccountQuery(
    UUID organizationId, UUID ownerPlatformAccountId) {}
