package com.clavaris.clientregistry.application.usecase.setredirectpolicyforclient;

import com.clavaris.clientregistry.domain.model.RedirectPolicy;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaRedirectPolicyRepository}. {@code findByOAuthClientId}
 * returning empty is the normal state for any {@code OAuthClient} that has never had this policy
 * configured — see {@link RedirectPolicy}'s own Javadoc.
 */
public interface RedirectPolicyRepository {

  Optional<RedirectPolicy> findByOAuthClientId(UUID oauthClientId);

  void save(RedirectPolicy policy);
}
