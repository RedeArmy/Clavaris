package com.clavaris.identity.application.usecase.starttotpenrollment;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.List;

/** Persistence port for the single-use recovery codes that stand in for the app (ADR-0040). */
public interface TotpRecoveryCodeRepository {

  /** Removes the Account's codes and stores {@code hashes} in their place. */
  void replaceAll(AccountId accountId, OrganizationId organizationId, List<String> hashes);

  /**
   * Spends the unspent code with this hash, in one statement, so a code works once even when two
   * requests carry it together.
   *
   * @return true when this call spent it
   */
  boolean consume(AccountId accountId, String codeHash);

  long countUnused(AccountId accountId);

  void deleteAll(AccountId accountId);
}
