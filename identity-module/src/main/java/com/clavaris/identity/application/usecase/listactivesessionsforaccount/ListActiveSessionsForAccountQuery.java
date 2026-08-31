package com.clavaris.identity.application.usecase.listactivesessionsforaccount;

import com.clavaris.identity.domain.model.AccountId;

/**
 * @param accountId always the caller's own resolved session principal ({@code
 *     AccountSessionsController}'s own {@code CurrentAccountResolver} call) — never client input,
 *     so this query can never be used to list another Account's sessions.
 */
public record ListActiveSessionsForAccountQuery(AccountId accountId) {}
