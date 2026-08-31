package com.clavaris.identity.application.usecase.authenticateplatformaccountwithsocialprovider;

import com.clavaris.identity.domain.model.PlatformSocialIdentity;
import com.clavaris.identity.domain.model.SocialProvider;
import java.util.Optional;

/**
 * {@link
 * com.clavaris.identity.application.usecase.authenticatewithsocialprovider.SocialIdentityRepository}'s
 * platform-tier sibling — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaPlatformSocialIdentityRepository}. Same mirroring
 * convention {@code PlatformAccountRepository} already establishes for its own pair.
 */
public interface PlatformSocialIdentityRepository {

  Optional<PlatformSocialIdentity> findByProviderAndProviderUserId(
      SocialProvider provider, String providerUserId);

  void save(PlatformSocialIdentity identity);
}
