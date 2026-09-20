package com.clavaris.identity.application.usecase.listconnectedaccountsforplatformaccount;

import com.clavaris.identity.application.usecase.authenticateplatformaccountwithsocialprovider.PlatformSocialIdentityRepository;
import com.clavaris.identity.domain.model.PlatformAccountId;
import java.util.List;

/** Orchestration for {@link ListConnectedAccountsForPlatformAccountUseCase}. */
public class ListConnectedAccountsForPlatformAccountService
    implements ListConnectedAccountsForPlatformAccountUseCase {

  private final PlatformSocialIdentityRepository identities;

  public ListConnectedAccountsForPlatformAccountService(
      final PlatformSocialIdentityRepository identities) {
    this.identities = identities;
  }

  @Override
  public List<ConnectedAccount> handle(final PlatformAccountId platformAccountId) {
    return identities.findAllByPlatformAccountId(platformAccountId).stream()
        .map(identity -> new ConnectedAccount(identity.provider(), identity.linkedAt()))
        .toList();
  }
}
