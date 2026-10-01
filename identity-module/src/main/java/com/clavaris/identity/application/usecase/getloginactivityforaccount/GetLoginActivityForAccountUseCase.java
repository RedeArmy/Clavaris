package com.clavaris.identity.application.usecase.getloginactivityforaccount;

import java.util.List;

/**
 * Inbound port — the web adapter depends on this interface, never on {@link
 * GetLoginActivityForAccountService} directly.
 */
@FunctionalInterface
public interface GetLoginActivityForAccountUseCase {

  List<LoginActivityDay> handle(GetLoginActivityForAccountQuery query);
}
