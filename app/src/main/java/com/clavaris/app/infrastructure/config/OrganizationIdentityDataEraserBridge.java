package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.SigningKeyRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.organization.application.usecase.deleteorganization.OrganizationIdentityDataEraser;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Implements organization-module's outbound port — the bridge lives in {@code app}, not either
 * business module, for the same module-graph reason {@code CreateOrganizationSigningKeyBridge}
 * does.
 *
 * <p>Accounts before signing keys: no ordering dependency exists between the two (both are leaves
 * under this Organization, neither references the other), but accounts first mirrors {@code
 * DeleteOrganizationService}'s own token-revocation-before-erasure ordering — erase the data that
 * could still authenticate before the data that only supports it.
 */
@Component
class OrganizationIdentityDataEraserBridge implements OrganizationIdentityDataEraser {

  private final AccountRepository accounts;
  private final SigningKeyRepository signingKeys;

  /* package */ OrganizationIdentityDataEraserBridge(
      final AccountRepository accounts, final SigningKeyRepository signingKeys) {
    this.accounts = accounts;
    this.signingKeys = signingKeys;
  }

  @Override
  public void eraseAllFor(final UUID organizationId) {
    final OrganizationId orgId = new OrganizationId(organizationId);
    // Cascades (V20260826100000, identity-module) to each Account's own password_credentials,
    // sessions, refresh_tokens, verification_tokens — same migration individual account deletion
    // already relies on.
    accounts.deleteAllByOrganizationId(orgId);
    signingKeys.deleteAllByOrganizationId(orgId);
  }
}
