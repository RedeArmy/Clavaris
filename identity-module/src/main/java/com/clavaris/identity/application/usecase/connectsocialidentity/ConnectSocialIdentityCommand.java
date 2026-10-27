package com.clavaris.identity.application.usecase.connectsocialidentity;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.SocialProvider;

/**
 * {@code providerUserId} is the provider's own subject, already authenticated by the OAuth dance.
 */
public record ConnectSocialIdentityCommand(
    AccountId accountId, SocialProvider provider, String providerUserId) {

  @Override
  public String toString() {
    return "ConnectSocialIdentityCommand[accountId=" + accountId + ", provider=" + provider + "]";
  }
}
