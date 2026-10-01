package com.clavaris.identity.application.usecase.getloginactivityforaccount;

import com.clavaris.identity.domain.model.AccountId;

/**
 * @param accountId resolved and ownership-checked by the caller ({@code
 *     PlatformAccountDetailController}'s own {@code PlatformAccountOrganizationAccess} check)
 *     before this query is ever built — same precedent {@code ListActiveSessionsForAccountQuery}'s
 *     own Javadoc already documents for this same "View Profile" page.
 */
public record GetLoginActivityForAccountQuery(AccountId accountId) {}
