package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.common.domain.model.KeysetCursor;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.common.infrastructure.adapter.out.persistence.SpringDataKeysetPageMapper;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.AccountMetadata;
import com.clavaris.identity.domain.model.AccountPermissions;
import com.clavaris.identity.domain.model.AccountProfile;
import com.clavaris.identity.domain.model.AccountSnapshot;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.ConcurrentAccountModificationException;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.PasswordCredential;
import com.clavaris.identity.domain.model.RegistrationDecision;
import com.clavaris.identity.domain.model.Username;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

// PMD.TooManyMethods: ADR-0024 §4 added two more methods (username lookup) to a repository
// implementing the one AccountRepository port — same "wiring, not sprawl" reasoning every other
// growing outbound-port implementation in this codebase already documents for an identical case.
// PMD.CouplingBetweenObjects: TD-PERF-027's new findAllByOrganizationIdAndIds (one more mapped
// domain/entity type in its signature) pushed this class's own count from 20 to 21 — the same
// "wiring, not sprawl" shape as TooManyMethods above, on the same class, for the same reason: one
// repository implementing one growing outbound port collaborates with every type that port needs.
/**
 * Implements the outbound port; maps between {@code domain.model} (framework-free) and the
 * {@code @Entity} classes in this package.
 */
// PMD.ExcessiveImports: the adapter maps an Account and its value objects to and from rows.
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CouplingBetweenObjects", "PMD.ExcessiveImports"})
@Repository
class JpaAccountRepository implements AccountRepository {

  private final SpringDataAccountJpaRepository accounts;
  private final SpringDataPasswordCredentialJpaRepository credentials;
  private final EntityManager entityManager;

  // Constructed only by Spring's own component scan (via @Repository above), never directly by
  // other code — AccountRepository (the port) is the only type callers outside this package
  // should depend on.
  /* package */ JpaAccountRepository(
      final SpringDataAccountJpaRepository accounts,
      final SpringDataPasswordCredentialJpaRepository credentials,
      final EntityManager entityManager) {
    this.accounts = accounts;
    this.credentials = credentials;
    this.entityManager = entityManager;
  }

  @Override
  public boolean existsByOrganizationIdAndEmail(
      final OrganizationId organizationId, final Email email) {
    return accounts.existsByOrganizationIdAndEmail(organizationId.value(), email.value());
  }

  @Override
  public Optional<Account> findByOrganizationIdAndEmail(
      final OrganizationId organizationId, final Email email) {
    return accounts
        .findByOrganizationIdAndEmail(organizationId.value(), email.value())
        .map(this::toDomain);
  }

  @Override
  public boolean existsByOrganizationIdAndUsername(
      final OrganizationId organizationId, final Username username) {
    return accounts.existsByOrganizationIdAndUsername(organizationId.value(), username.value());
  }

  @Override
  public Optional<Account> findByOrganizationIdAndUsername(
      final OrganizationId organizationId, final Username username) {
    return accounts
        .findByOrganizationIdAndUsername(organizationId.value(), username.value())
        .map(this::toDomain);
  }

  @Override
  public Optional<Account> findById(final AccountId accountId) {
    return accounts.findById(accountId.value()).map(this::toDomain);
  }

  @Override
  public Optional<OrganizationId> findOrganizationIdById(final AccountId accountId) {
    return accounts.findOrganizationIdById(accountId.value()).map(OrganizationId::new);
  }

  @Override
  public List<Account> findAllByOrganizationIdAndIds(
      final OrganizationId organizationId, final Collection<AccountId> accountIds) {
    final List<UUID> ids = accountIds.stream().map(AccountId::value).toList();
    return accounts.findByOrganizationIdAndIdIn(organizationId.value(), ids).stream()
        .map(this::toDomain)
        .toList();
  }

  private Account toDomain(final AccountEntity entity) {
    return toDomain(entity, credentials.findByAccountId(entity.getId()).orElse(null));
  }

  // TD-PERF-029: the batch half of toDomain — findKeysetPageByOrganizationId's own list view never
  // renders PasswordCredential at all, so paying for a per-row findByAccountId query on every page
  // was pure waste even before this fix; now it's one findByAccountIdIn per page.
  private Account toDomain(
      final AccountEntity entity, final PasswordCredentialEntity credentialRow) {
    final AccountId accountId = new AccountId(entity.getId());
    // A password-only login attempt against an account that only has a social identity attached
    // (ADR-0020) is exactly the case reconstitute's own Javadoc calls out — absent here is not a
    // bug, AuthenticateWithPasswordService treats it as "no password credential to check".
    final PasswordCredential credential =
        credentialRow == null
            ? null
            : PasswordCredential.reconstitute(
                credentialRow.getId(),
                accountId,
                credentialRow.getPasswordHash(),
                credentialRow.getUpdatedAt());
    final Username username =
        entity.getUsername() == null ? null : new Username(entity.getUsername());
    return Account.reconstitute(
        new AccountSnapshot(
            accountId,
            new OrganizationId(entity.getOrganizationId()),
            new Email(entity.getEmail()),
            entity.getCreatedAt(),
            entity.getEmailVerifiedAt(),
            AccountStatus.valueOf(entity.getStatus()),
            credential,
            username,
            entity.getPasswordResetRequiredAt(),
            entity.getLastSignedInAt(),
            new AccountProfile(
                entity.getFirstName(),
                entity.getLastName(),
                entity.getPhoneNumber(),
                entity.getPictureUrl()),
            new AccountPermissions(entity.isCanDeleteOwnAccount(), entity.isBypassesDeviceTrust()),
            new RegistrationDecision(
                entity.getRegistrationDecidedAt(), entity.getRegistrationRejectionReason()),
            new AccountMetadata(
                entity.getPublicMetadata(),
                entity.getPrivateMetadata(),
                entity.getUnsafeMetadata()),
            entity.getVersion()));
  }

  @Override
  @Transactional
  public void recordSignIn(final AccountId accountId, final Instant signedInAt) {
    accounts.updateLastSignedInAt(accountId.value(), signedInAt);
  }

  @Override
  @Transactional
  public void recordSignInVerifyingEmail(
      final AccountId accountId, final Instant signedInAt, final Instant verifiedAt) {
    accounts.updateLastSignedInAtVerifyingEmail(accountId.value(), signedInAt, verifiedAt);
  }

  // TD-PERF-028 (closed): no longer also writes the credential — see AccountRepository#save's own
  // Javadoc for why (20 of 22 real callers never touch it) and #saveCredential for the separate
  // write the two real mutating callers now use instead.
  @Override
  @Transactional
  public void save(final Account account) {
    // saveAndFlush, not save: the unique constraint on accounts.(organization_id, email)
    // (data-model.md §3) must throw synchronously, right here, so RegisterAccountService's
    // try/catch actually catches it. Plain save() only stages the insert in the persistence
    // context — Hibernate would defer executing it until the surrounding @Transactional method
    // returns and the transaction commits, by which point the try/catch block has already
    // exited and the exception would surface somewhere the service never expects it.
    try {
      final AccountEntity saved = accounts.saveAndFlush(toEntity(account));
      // The aggregate may be saved again in the same flow; it must carry the version just written.
      account.rememberPersistedVersion(saved.getVersion());
    } catch (final OptimisticLockingFailureException _) {
      // TD-ARCH-037: flushed above so the version check runs here, inside this try, not later at
      // the caller's commit where this catch could not see it (the OAuthClient precedent).
      throw new ConcurrentAccountModificationException(account.id());
    }
  }

  // TD-PERF-028: the credential half save() used to always do unconditionally — same
  // saveAndFlush-for-synchronous-exception-surfacing reasoning as save() itself.
  //
  // Sonar S125 false positive (reworded, not NOSONAR'd — same convention
  // RateLimitingIntegrationTest's own identical fix documents): the account and credential
  // writes must share one commit boundary. Both real mutating callers — ConfirmPasswordResetService
  // and CompleteForcedPasswordResetService — are themselves transactional (a code review finding,
  // SDE-III design Phase 2 #8, migration V20260830110000's own deferred BR-ID-02 trigger), and
  // Spring's default REQUIRED propagation joins that already-open transaction here with zero
  // behavior change, the same way it does for save() above.
  @Override
  @Transactional
  public void saveCredential(final Account account) {
    saveCredentialIfPresent(account, credentials::saveAndFlush);
  }

  // TD-PERF-019: same write as save() above, for the two call sites (RegisterAccountService,
  // AuthenticateWithSocialProviderService#linkBrandNewAccount) that know for a fact this Account
  // has never been persisted before — entityManager.persist() skips the pre-existence SELECT
  // save()'s own merge()-based path always issues, correct for an update but pure waste on a row
  // both caller and callee already know is new. Explicit flush() for the exact same synchronous-
  // exception-surfacing reason save()'s own saveAndFlush comment already documents — persist()
  // alone only stages the insert, same as a bare save() would.
  @Override
  @Transactional
  public void insert(final Account account) {
    entityManager.persist(toEntity(account));
    saveCredentialIfPresent(account, entityManager::persist);
    entityManager.flush();
  }

  private AccountEntity toEntity(final Account account) {
    return new AccountEntity(
        account.id().value(),
        account.organizationId().value(),
        account.email().value(),
        account.emailVerifiedAt().orElse(null),
        account.status().name(),
        account.createdAt(),
        account.username().map(Username::value).orElse(null),
        account.passwordResetRequiredAt().orElse(null),
        account.firstName().orElse(null),
        account.lastName().orElse(null),
        account.phoneNumber().orElse(null),
        account.lastSignedInAt().orElse(null),
        account.pictureUrl().orElse(null),
        account.canDeleteOwnAccount(),
        account.bypassesDeviceTrust(),
        account.registrationDecidedAt().orElse(null),
        account.registrationRejectionReason().orElse(null),
        account.publicMetadata().orElse(null),
        account.privateMetadata().orElse(null),
        account.unsafeMetadata().orElse(null),
        account.snapshot().version());
  }

  // ADR-0020 (Phase 6, live-verified): a brand-new social signup (AuthenticateWithSocialProvider
  // Service#linkBrandNewAccount) saves an Account with no PasswordCredential at all — BR-ID-02's
  // real "never zero auth methods" invariant is upheld one level up, by that same transaction
  // also saving a SocialIdentity for the same account, not by this repository unconditionally
  // requiring a password specifically. Account.reconstitute's own Javadoc already documented a
  // null credential as a legitimate social-only state; this method previously still threw on it —
  // a real, previously-undetected gap this phase's own integration test caught live (a 500 on the
  // very first real social signup), not a hypothetical one. Only persist a row here if the
  // aggregate actually carries one.
  //
  // Code review finding: this removes the persistence layer's own fail-fast enforcement of
  // BR-ID-02 for every caller, not just the social-login ones — every current caller happens to
  // also save a SocialIdentity/PasswordCredential in the same transaction, so nothing breaks
  // today, but nothing here would catch a future regression that doesn't. An application-layer
  // synchronous guard can't work at this exact call site regardless — a genuinely synchronous
  // guard now does exist, just not here: migration V20260830110000's own DEFERRABLE INITIALLY
  // DEFERRED constraint trigger fires at transaction commit (after this same transaction's own
  // SocialIdentity/PasswordCredential insert has already run, whichever order they happened in)
  // and rejects the whole insert. AccountAuthMethodIntegrityCheckJob remains a second,
  // independent daily sweep on top of that trigger, not the primary control anymore — see
  // either one's own Javadoc for the full reasoning.
  private void saveCredentialIfPresent(
      final Account account, final Consumer<PasswordCredentialEntity> saver) {
    account
        .passwordCredential()
        .ifPresent(
            credential ->
                saver.accept(
                    new PasswordCredentialEntity(
                        credential.id(),
                        credential.accountId().value(),
                        credential.passwordHash(),
                        credential.updatedAt())));
  }

  @Override
  public void deleteById(final AccountId accountId) {
    // No existence check or exception here on purpose — the caller (DeleteAccountService) already
    // did its own findById lookup before deciding to call this at all, and Spring Data's own
    // delete-by-id is a no-op, not an error, if the row somehow already gone.
    //
    // The explicit flush below matches the "must actually reach Postgres now, not whenever the
    // surrounding transaction happens to commit" reasoning as save()'s own saveAndFlush above — a
    // bare delete only stages the removal in the persistence context. Nothing in this class's own
    // production call path strictly depends on that timing today, but leaving it deferred is a
    // real footgun for any future caller (or test) that reads this same row through a different
    // connection/path within the same transaction, exactly the class of bug save()'s own comment
    // already flags.
    accounts.deleteById(accountId.value());
    accounts.flush();
  }

  @Override
  public void deleteAllByOrganizationId(final OrganizationId organizationId) {
    accounts.deleteAllByOrganizationId(organizationId.value());
    accounts.flush();
  }

  @Override
  public List<AccountId> findAllAccountIdsByOrganizationId(final OrganizationId organizationId) {
    return accounts.findIdsByOrganizationId(organizationId.value()).stream()
        .map(AccountId::new)
        .toList();
  }

  @Override
  public KeysetPage<Account> findKeysetPageByOrganizationId(
      final OrganizationId organizationId,
      final KeysetPageRequest pageRequest,
      final String searchTerm) {
    final String pattern = likePattern(searchTerm);
    final PageRequest limit = PageRequest.of(0, pageRequest.size() + 1);
    // TD-PERF-041: with a search, the *Searching queries (always a pattern, so a trigram index
    // stays
    // usable in a generic plan too); without one, the plain keyset queries.
    final boolean searching = pattern != null;
    if (pageRequest.after() != null) {
      final KeysetCursor cursor = pageRequest.after();
      final List<AccountEntity> fetched =
          searching
              ? accounts.findPageByOrganizationIdAfterSearching(
                  organizationId.value(), cursor.createdAt(), cursor.id(), pattern, limit)
              : accounts.findPageByOrganizationIdAfter(
                  organizationId.value(), cursor.createdAt(), cursor.id(), limit);
      return SpringDataKeysetPageMapper.forward(
          fetched, pageRequest.size(), true, toDomainBatched(fetched), this::cursorOf);
    }
    if (pageRequest.before() != null) {
      final KeysetCursor cursor = pageRequest.before();
      final List<AccountEntity> fetched =
          searching
              ? accounts.findPageByOrganizationIdBeforeSearching(
                  organizationId.value(), cursor.createdAt(), cursor.id(), pattern, limit)
              : accounts.findPageByOrganizationIdBefore(
                  organizationId.value(), cursor.createdAt(), cursor.id(), limit);
      return SpringDataKeysetPageMapper.backward(
          fetched, pageRequest.size(), toDomainBatched(fetched), this::cursorOf);
    }
    final List<AccountEntity> fetched =
        searching
            ? accounts.findFirstPageByOrganizationIdSearching(
                organizationId.value(), pattern, limit)
            : accounts.findFirstPageByOrganizationId(organizationId.value(), limit);
    return SpringDataKeysetPageMapper.forward(
        fetched, pageRequest.size(), false, toDomainBatched(fetched), this::cursorOf);
  }

  // A blank term means no search. Otherwise "%term%" with the term's own LIKE metacharacters
  // escaped with '!' (the queries say ESCAPE '!'), so typing % or _ searches for that
  // character instead of matching every row.
  private static String likePattern(final String searchTerm) {
    if (searchTerm == null || searchTerm.isBlank()) {
      return null;
    }
    final String escaped =
        searchTerm.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_");
    return "%" + escaped + "%";
  }

  private Function<AccountEntity, Account> toDomainBatched(final List<AccountEntity> entities) {
    final Map<UUID, PasswordCredentialEntity> credentialsByAccountId =
        credentials.findByAccountIdIn(entities.stream().map(AccountEntity::getId).toList()).stream()
            .collect(Collectors.toMap(PasswordCredentialEntity::getAccountId, Function.identity()));
    return entity -> toDomain(entity, credentialsByAccountId.get(entity.getId()));
  }

  private KeysetCursor cursorOf(final AccountEntity entity) {
    return new KeysetCursor(entity.getCreatedAt(), entity.getId());
  }
}
