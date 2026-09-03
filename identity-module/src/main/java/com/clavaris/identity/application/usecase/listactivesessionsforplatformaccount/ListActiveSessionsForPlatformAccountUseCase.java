package com.clavaris.identity.application.usecase.listactivesessionsforplatformaccount;

import java.util.List;

/**
 * Inbound port — the web adapter depends on this interface, never on {@link
 * ListActiveSessionsForPlatformAccountService} directly.
 */
@FunctionalInterface
public interface ListActiveSessionsForPlatformAccountUseCase {

  List<ActivePlatformAccountSession> handle(ListActiveSessionsForPlatformAccountQuery query);
}
