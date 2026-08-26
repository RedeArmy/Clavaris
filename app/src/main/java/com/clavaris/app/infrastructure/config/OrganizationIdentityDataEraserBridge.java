package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.SigningKeyRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountSessionRevoker;
import com.clavaris.identity.domain.model.Account;
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
 *
 * <p>TD-SEC-031 (SDE-III review, 2026-08-26): every live {@code HttpSession} for every Account this
 * Organization owns is revoked here, individually, before the bulk delete — {@code
 * DeleteOrganizationService}'s own {@code OrganizationTokenRevoker} step only reaches the
 * SAS-managed token/authorization rows, same gap {@code AccountSessionRevoker}'s own Javadoc
 * already documents for the single-account case. A bulk {@code deleteAllByOrganizationId} has no
 * per-row hook to revoke from, hence the read (({@link #accounts}{@code .findAllByOrganizationId})
 * immediately before it, purely to drive this loop.
 */
@Component
class OrganizationIdentityDataEraserBridge implements OrganizationIdentityDataEraser {

  private final AccountRepository accounts;
  private final SigningKeyRepository signingKeys;

  @SuppressWarnings("PMD.LongVariable") // matches the port's own name, same precedent as every
  // other caller of this port (ConfirmPasswordResetService, RotateRefreshTokenService,
  // DeleteAccountService).
  private final AccountSessionRevoker accountSessionRevoker;

  /* package */ OrganizationIdentityDataEraserBridge(
      final AccountRepository accounts,
      final SigningKeyRepository signingKeys,
      @SuppressWarnings("PMD.LongVariable") final AccountSessionRevoker accountSessionRevoker) {
    this.accounts = accounts;
    this.signingKeys = signingKeys;
    this.accountSessionRevoker = accountSessionRevoker;
  }

  @Override
  public void eraseAllFor(final UUID organizationId) {
    final OrganizationId orgId = new OrganizationId(organizationId);

    for (final Account account : accounts.findAllByOrganizationId(orgId)) {
      accountSessionRevoker.revokeAllSessionsFor(account.id());
    }

    // Cascades (V20260826100000, identity-module) to each Account's own password_credentials,
    // sessions, refresh_tokens, verification_tokens — same migration individual account deletion
    // already relies on.
    accounts.deleteAllByOrganizationId(orgId);
    signingKeys.deleteAllByOrganizationId(orgId);
  }
}
