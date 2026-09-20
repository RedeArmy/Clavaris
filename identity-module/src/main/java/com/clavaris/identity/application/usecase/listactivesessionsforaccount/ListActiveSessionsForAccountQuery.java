package com.clavaris.identity.application.usecase.listactivesessionsforaccount;

import com.clavaris.identity.domain.model.AccountId;

/**
 * @param accountId the self-service caller's own resolved session principal ({@code
 *     AccountSessionsController}'s own {@code CurrentAccountResolver} call) — never client input
 *     there. SDE-III review, 2026-09-21: {@code PlatformAccountDetailController} (dashboard "View
 *     Profile" > Profile tab "Devices" section) is a second, legitimate caller where this genuinely
 *     is client input (a path variable) — this is a pure read with no audit trail to misattribute,
 *     safe to reuse as-is, gated by that controller's own {@code PlatformAccountOrganizationAccess}
 *     organization-ownership check before this query is ever built.
 */
public record ListActiveSessionsForAccountQuery(AccountId accountId) {}
