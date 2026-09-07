package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.SigningKeyRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountSessionRevoker;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.infrastructure.adapter.out.security.OrganizationSigningKeyMaterialFactory;
import com.clavaris.organization.application.usecase.deleteorganization.OrganizationIdentityDataEraser;
import java.util.List;
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
 * per-row hook to revoke from, hence the read (({@link #accounts}{@code
 * .findAllAccountIdsByOrganizationId}) immediately before it, purely to drive this loop.
 * TD-PERF-016: an id-only projection, not the full {@code Account} the original version of this
 * read fetched — this loop only ever needed {@code account.id()}, so the full aggregate (and the
 * separate password-credential query per row it forced) was pure waste on the single most
 * destructive operation this system exposes.
 *
 * <p>TD-SEC-052 (SDE-III review, 2026-09-06): {@code signing_keys} rows and {@link SigningKeyStore}
 * entries are two independent stores of the same key material — deleting the DB rows alone (as this
 * class used to do) left every historical {@code kid} this Organization ever rotated through
 * sitting in the PKCS12 file (and, for whichever key was still active, in {@link
 * OrganizationSigningKeyMaterialFactory}'s own in-memory cache) forever, the one place TD-SEC-029's
 * emergency-purge discipline never reached. {@link #signingKeys}{@code
 * .findAllKidsByOrganizationId} is read <em>before</em> {@code deleteAllByOrganizationId} for the
 * same reason accounts are read before their own bulk delete above — the DB rows are the only place
 * this Organization's full kid history is enumerable, and once gone, gone.
 */
@Component
class OrganizationIdentityDataEraserBridge implements OrganizationIdentityDataEraser {

  private final AccountRepository accounts;
  private final SigningKeyRepository signingKeys;
  private final OrganizationSigningKeyMaterialFactory keyMaterial;

  @SuppressWarnings("PMD.LongVariable") // matches the port's own name, same precedent as every
  // other caller of this port (ConfirmPasswordResetService, RotateRefreshTokenService,
  // DeleteAccountService).
  private final AccountSessionRevoker accountSessionRevoker;

  /* package */ OrganizationIdentityDataEraserBridge(
      final AccountRepository accounts,
      final SigningKeyRepository signingKeys,
      final OrganizationSigningKeyMaterialFactory keyMaterial,
      @SuppressWarnings("PMD.LongVariable") final AccountSessionRevoker accountSessionRevoker) {
    this.accounts = accounts;
    this.signingKeys = signingKeys;
    this.keyMaterial = keyMaterial;
    this.accountSessionRevoker = accountSessionRevoker;
  }

  @Override
  public void eraseAllFor(final UUID organizationId) {
    final OrganizationId orgId = new OrganizationId(organizationId);

    for (final AccountId accountId : accounts.findAllAccountIdsByOrganizationId(orgId)) {
      accountSessionRevoker.revokeAllSessionsFor(accountId);
    }

    // TD-SEC-052: must be read before the deleteAllByOrganizationId call below — that's the only
    // place this Organization's full kid history (active and long-retired alike) is enumerable.
    final List<String> kids = signingKeys.findAllKidsByOrganizationId(orgId);

    // Cascades (V20260826100000, identity-module) to each Account's own password_credentials,
    // sessions, refresh_tokens, verification_tokens — same migration individual account deletion
    // already relies on.
    accounts.deleteAllByOrganizationId(orgId);
    signingKeys.deleteAllByOrganizationId(orgId);

    // TD-SEC-052: last step — evicts the in-memory cache entry (if any) and every historical kid's
    // own PKCS12 entry, the purge TD-SEC-029's emergency-revocation discipline never reached.
    keyMaterial.purgeAllFor(orgId, kids);
  }
}
