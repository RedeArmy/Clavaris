package com.clavaris.identity.application.usecase.authenticatewithsocialprovider;

import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SocialIdentity;
import com.clavaris.identity.domain.model.SocialProvider;
import java.util.Optional;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaSocialIdentityRepository}. {@code (organizationId,
 * provider, providerUserId)} is unique (data-model.md, {@code social_identities} table) — the one
 * lookup a returning social login needs to resolve straight to an {@link
 * com.clavaris.identity.domain.model.AccountId} without ever touching email at all.
 *
 * <p>CLAUDE.md §5: scoped by {@code organizationId}, deliberately not a bare {@code (provider,
 * providerUserId)} lookup — the same real-world Google/GitHub identity can legitimately link to two
 * different Accounts in two different Organizations (each owns a fully isolated Account pool), so
 * omitting organizationId here would let a login through one Organization resolve an Account that
 * actually belongs to another (code review finding, fixed before ever shipping).
 */
public interface SocialIdentityRepository {

  Optional<SocialIdentity> findByOrganizationIdAndProviderAndProviderUserId(
      OrganizationId organizationId, SocialProvider provider, String providerUserId);

  void save(SocialIdentity identity);
}
