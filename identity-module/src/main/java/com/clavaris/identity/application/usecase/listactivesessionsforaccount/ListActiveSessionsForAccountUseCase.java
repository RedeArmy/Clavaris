package com.clavaris.identity.application.usecase.listactivesessionsforaccount;

import java.util.List;

/**
 * Inbound port — the web adapter depends on this interface, never on {@link
 * ListActiveSessionsForAccountService} directly.
 */
@FunctionalInterface
public interface ListActiveSessionsForAccountUseCase {

  List<ActiveAccountSession> handle(ListActiveSessionsForAccountQuery query);
}
