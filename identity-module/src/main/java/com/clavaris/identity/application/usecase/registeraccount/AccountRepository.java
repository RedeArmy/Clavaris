package com.clavaris.identity.application.usecase.registeraccount;

import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.Optional;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaAccountRepository}. Parked under {@code
 * registeraccount} because that's this module's first use case, not because {@code
 * findByOrganizationIdAndEmail} is scoped to it — {@code
 * application.usecase.authenticatewithpassword.AuthenticateWithPasswordService} is the second
 * consumer, same precedent as organization-module's own {@code OrganizationRepository.existsById}.
 */
public interface AccountRepository {

  /**
   * Fast-path pre-check only, not the actual safety guarantee against a concurrent double
   * registration — see {@link RegisterAccountService} for why.
   */
  boolean existsByOrganizationIdAndEmail(OrganizationId organizationId, Email email);

  /**
   * BR-ORG-02: scoped by {@code organizationId}, never a global email lookup — a login screen must
   * never have a code path capable of resolving an {@code Account} from a different Organization.
   * The returned {@code Account} carries its attached {@code PasswordCredential}, if any — the
   * whole point of this lookup existing is to let {@code AuthenticateWithPasswordService} verify
   * against it.
   */
  Optional<Account> findByOrganizationIdAndEmail(OrganizationId organizationId, Email email);

  /**
   * BR-ID-03: {@code RotateRefreshTokenService} uses this to resolve {@code organizationId} for
   * {@code RefreshTokenReuseDetectedEvent} — the only fact a bare {@link AccountId} can't carry.
   */
  Optional<Account> findById(AccountId accountId);

  /** Persists the account and its attached credential in one write. */
  void save(Account account);
}
