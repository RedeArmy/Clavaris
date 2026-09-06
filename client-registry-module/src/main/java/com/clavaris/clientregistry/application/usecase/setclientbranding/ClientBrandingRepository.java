package com.clavaris.clientregistry.application.usecase.setclientbranding;

import com.clavaris.clientregistry.domain.model.ClientBranding;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaClientBrandingRepository}. {@code findByOAuthClientId}
 * returning empty is the normal state for any {@code OAuthClient} that has never had this
 * configured — see {@link ClientBranding}'s own Javadoc.
 */
public interface ClientBrandingRepository {

  Optional<ClientBranding> findByOAuthClientId(UUID oauthClientId);

  void save(ClientBranding branding);
}
