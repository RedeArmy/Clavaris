package com.clavaris.clientregistry.application.usecase.getclientdomainconfig;

import com.clavaris.clientregistry.domain.model.ClientDomainConfig;
import java.util.UUID;

/**
 * Read side of the domain-config surface — never empty for a real, owned {@code OAuthClient}, same
 * convention every GET-side use case here follows.
 *
 * <p>SDE-III review, 2026-09-15: {@code organizationId} is a real, enforced parameter — see {@code
 * GetClientDomainConfigService}'s own Javadoc for the cross-tenant read this closes.
 *
 * @throws OAuthClientNotFoundException if no {@code OAuthClient} with {@code oauthClientId} exists,
 *     or one does but belongs to a different Organization than {@code organizationId}
 */
@FunctionalInterface
public interface GetClientDomainConfigUseCase {

  ClientDomainConfig handle(UUID organizationId, UUID oauthClientId);
}
