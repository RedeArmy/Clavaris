package com.clavaris.identity.application.usecase.starttotpenrollment;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.TotpCredential;
import java.util.Optional;

/** Persistence port for an Account's authenticator-app secret (ADR-0040). */
public interface TotpCredentialRepository {

  Optional<TotpCredential> findByAccountId(AccountId accountId);

  /** Inserts, or replaces what an abandoned setup left, keyed by the Account. */
  void save(TotpCredential credential);

  /** True when the Account has a credential that was confirmed. */
  boolean existsConfirmed(AccountId accountId);

  /**
   * Records {@code step} as spent only if it is later than what is recorded, in one statement, so
   * two requests carrying the same code cannot both succeed.
   *
   * @return true when this call is the one that spent it
   */
  boolean spendStep(AccountId accountId, long step);

  /**
   * @return true when a credential was removed
   */
  boolean deleteByAccountId(AccountId accountId);

  /**
   * Removes what an abandoned setup left and never a confirmed credential, in one statement, so a
   * confirmation that commits first cannot be wiped by a setup that started before it.
   *
   * @return true when an unconfirmed credential was removed
   */
  boolean deleteUnconfirmedByAccountId(AccountId accountId);
}
