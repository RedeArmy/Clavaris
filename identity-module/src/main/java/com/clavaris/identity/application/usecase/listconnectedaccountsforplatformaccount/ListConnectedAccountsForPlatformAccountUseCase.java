package com.clavaris.identity.application.usecase.listconnectedaccountsforplatformaccount;

import com.clavaris.identity.domain.model.PlatformAccountId;
import java.util.List;

/** The self-service "Manage account" > Profile tab's own "Connected accounts" section. */
@FunctionalInterface
public interface ListConnectedAccountsForPlatformAccountUseCase {

  List<ConnectedAccount> handle(PlatformAccountId platformAccountId);
}
