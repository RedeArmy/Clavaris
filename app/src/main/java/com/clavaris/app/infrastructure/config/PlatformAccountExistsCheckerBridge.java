package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.organization.application.usecase.createorganization.PlatformAccountExistsChecker;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Adapts identity-module's {@code PlatformAccountRepository.findById} to organization-module's
 * {@link PlatformAccountExistsChecker} outbound port — the bridge lives in {@code app}, not either
 * business module, same convention as {@code OrganizationExistsCheckerBridge}: it needs both at
 * once and {@code app} is the one module allowed to (the module-graph's dependency rule).
 */
@Component
class PlatformAccountExistsCheckerBridge implements PlatformAccountExistsChecker {

  private final PlatformAccountRepository platformAccounts;

  /* package */ PlatformAccountExistsCheckerBridge(
      final PlatformAccountRepository platformAccounts) {
    this.platformAccounts = platformAccounts;
  }

  @Override
  public boolean exists(final UUID platformAccountId) {
    return platformAccounts.findById(new PlatformAccountId(platformAccountId)).isPresent();
  }
}
