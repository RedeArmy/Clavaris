package com.clavaris.identity.application.usecase.registeraccount;

import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaAccountRepository}.
 */
public interface AccountRepository {

  /**
   * Fast-path pre-check only, not the actual safety guarantee against a concurrent double
   * registration — see {@link RegisterAccountService} for why.
   */
  boolean existsByOrganizationIdAndEmail(OrganizationId organizationId, Email email);

  /** Persists the account and its attached credential in one write. */
  void save(Account account);
}
