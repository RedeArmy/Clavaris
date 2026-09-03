package com.clavaris.identity.application.usecase.listactivesessionsforplatformaccount;

import com.clavaris.identity.domain.model.PlatformAccountId;

/**
 * @param platformAccountId always the caller's own resolved session principal (this package's own
 *     {@code CurrentPlatformAccountResolver} call) — never client input.
 */
public record ListActiveSessionsForPlatformAccountQuery(PlatformAccountId platformAccountId) {}
