package com.clavaris.identity.domain.event;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SocialIdentity;
import com.clavaris.identity.domain.model.SocialProvider;
import java.time.Instant;

/**
 * ADR-0020 Decision 1: {@code social_identity.linked} — fires for both branches that end in a real
 * {@link SocialIdentity} row: a brand-new self-service social signup (immediate) and a {@code
 * ConfirmPendingSocialLinkService} confirmation (delayed). Written to the transactional outbox in
 * the same database transaction as the {@code SocialIdentity} insert (ADR-0007 §1) — this record is
 * only the payload shape, not the delivery mechanism.
 */
public record SocialIdentityLinkedEvent(
    AccountId accountId,
    OrganizationId organizationId,
    SocialProvider provider,
    Instant occurredAt) {

  public static SocialIdentityLinkedEvent from(
      final SocialIdentity identity, final OrganizationId organizationId) {
    return new SocialIdentityLinkedEvent(
        identity.accountId(), organizationId, identity.provider(), Instant.now());
  }
}
