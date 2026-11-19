package com.clavaris.app.infrastructure.adapter.out.bridge;

import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.SigningKeyRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountSessionRevoker;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.infrastructure.adapter.out.security.OrganizationSigningKeyMaterialFactory;
import com.clavaris.organization.application.usecase.deleteorganization.OrganizationIdentityDataEraser;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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

  private static final Logger LOG =
      LoggerFactory.getLogger(OrganizationIdentityDataEraserBridge.class);

  private final AccountRepository accounts;
  private final SigningKeyRepository signingKeys;
  private final OrganizationSigningKeyMaterialFactory keyMaterial;

  private final AccountSessionRevoker accountSessionRevoker;

  // TD-SEC-102: the closing of each account's sessions runs after the commit, where the original
  // transaction is finished and a write needs one of its own.
  private final TransactionTemplate ownTransaction;

  /* package */ OrganizationIdentityDataEraserBridge(
      final AccountRepository accounts,
      final SigningKeyRepository signingKeys,
      final OrganizationSigningKeyMaterialFactory keyMaterial,
      final AccountSessionRevoker accountSessionRevoker,
      final PlatformTransactionManager transactionManager) {
    this.accounts = accounts;
    this.signingKeys = signingKeys;
    this.keyMaterial = keyMaterial;
    this.accountSessionRevoker = accountSessionRevoker;
    this.ownTransaction = new TransactionTemplate(transactionManager);
    this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public Runnable eraseAllFor(final UUID organizationId) {
    final OrganizationId orgId = new OrganizationId(organizationId);

    // Read now, while the rows exist: the ids to revoke sessions for, and (TD-SEC-052) the full kid
    // history, which only the signing_keys rows enumerate and which is gone once they are deleted.
    final List<AccountId> accountIds = accounts.findAllAccountIdsByOrganizationId(orgId);
    final List<String> kids = signingKeys.findAllKidsByOrganizationId(orgId);

    // Cascades (V20260826100000, identity-module) to each Account's own password_credentials,
    // sessions, refresh_tokens, verification_tokens — same migration individual account deletion
    // already relies on.
    accounts.deleteAllByOrganizationId(orgId);
    signingKeys.deleteAllByOrganizationId(orgId);

    // TD-PERF-043: the slow part waits for the commit. One Redis lookup per account and a rewrite
    // of
    // the key file used to run inside the transaction, holding a pooled connection (and, for the
    // key file, state that a rollback could not restore) for minutes on a large tenant.
    return () -> {
      // TD-SEC-102: the key purge first, each step on its own. Closing sessions needs Redis and,
      // with Redis down, a database write; a failure there used to end this action before the purge
      // ran and left the Organization's private keys on disk for good. The purge needs neither, and
      // what it misses is caught by OrphanedSigningKeyFileCleanupJob.
      purgeKeyMaterial(orgId, kids);
      closeSessions(accountIds);
    };
  }

  // PMD.AvoidCatchingGenericException: a failure in one step must never skip the other, and this
  // action runs after the commit, where there is nobody left to tell.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private void purgeKeyMaterial(final OrganizationId orgId, final List<String> kids) {
    try {
      // TD-SEC-052: evicts the in-memory cache entry (if any) and every historical kid's own PKCS12
      // entry, the purge TD-SEC-029's emergency-revocation discipline never reached.
      keyMaterial.purgeAllFor(orgId, kids);
    } catch (final RuntimeException failure) {
      LOG.error(
          "event=organization_key_purge_failed reason={}", failure.getClass().getSimpleName());
    }
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private void closeSessions(final List<AccountId> accountIds) {
    for (final AccountId accountId : accountIds) {
      try {
        ownTransaction.executeWithoutResult(
            status -> accountSessionRevoker.revokeAllSessionsFor(accountId));
      } catch (final RuntimeException failure) {
        LOG.error(
            "event=organization_session_close_failed reason={}",
            failure.getClass().getSimpleName());
      }
    }
  }
}
