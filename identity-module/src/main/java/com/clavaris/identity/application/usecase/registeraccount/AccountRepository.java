package com.clavaris.identity.application.usecase.registeraccount;

import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.List;
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

  /**
   * BR-DATA-02/03: a real, permanent hard delete — the only delete this port (or any repository in
   * this module) exposes. Cascades at the database level to every table whose own row exists only
   * because this {@code Account} does (migration {@code V20260826100000}) — see {@code
   * DeleteAccountService}'s own Javadoc for the full reasoning.
   */
  void deleteById(AccountId accountId);

  /**
   * BR-DATA-02/03's own organization-level equivalent — every {@code Account} this Organization
   * owns, hard-deleted, cascading (same migration) to each one's own {@code
   * password_credentials}/{@code sessions}/{@code refresh_tokens}/{@code verification_tokens}.
   */
  void deleteAllByOrganizationId(OrganizationId organizationId);

  /**
   * TD-SEC-031: {@code OrganizationIdentityDataEraserBridge}'s own read before it calls {@link
   * #deleteAllByOrganizationId} — the ids are needed to revoke each Account's own live {@code
   * HttpSession} (via {@code AccountSessionRevoker}) before the row it belongs to disappears, since
   * a bulk delete alone has no per-account hook to do that from.
   */
  List<Account> findAllByOrganizationId(OrganizationId organizationId);
}
