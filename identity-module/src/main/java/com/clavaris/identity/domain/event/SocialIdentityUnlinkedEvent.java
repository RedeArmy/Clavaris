package com.clavaris.identity.domain.event;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SocialProvider;
import java.time.Instant;

/**
 * {@code social_identity.unlinked} (TD-FUT-043): the End-User removed a Google/GitHub identity from
 * their Account. The counterpart of {@link SocialIdentityLinkedEvent}, so a consumer that tracks
 * which sign-in methods an Account has stays correct.
 */
public record SocialIdentityUnlinkedEvent(
    AccountId accountId,
    OrganizationId organizationId,
    SocialProvider provider,
    Instant occurredAt) {

  public static SocialIdentityUnlinkedEvent of(
      final AccountId accountId,
      final OrganizationId organizationId,
      final SocialProvider provider) {
    return new SocialIdentityUnlinkedEvent(accountId, organizationId, provider, Instant.now());
  }
}
