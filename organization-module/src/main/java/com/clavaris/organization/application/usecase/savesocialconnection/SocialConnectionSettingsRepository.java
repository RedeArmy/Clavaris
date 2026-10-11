package com.clavaris.organization.application.usecase.savesocialconnection;

import com.clavaris.organization.domain.model.SocialConnectionSettings;
import com.clavaris.organization.domain.model.SocialProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for {@link SocialConnectionSettings}. A missing row is an ordinary answer, not
 * an error: it means the Organization never saved this connection (see the class's own Javadoc).
 */
public interface SocialConnectionSettingsRepository {

  Optional<SocialConnectionSettings> findByOrganizationIdAndProvider(
      UUID organizationId, SocialProvider provider);

  List<SocialConnectionSettings> findAllByOrganizationId(UUID organizationId);

  /** Inserts the settings, or replaces the Organization's current ones for that provider. */
  void save(SocialConnectionSettings settings);
}
