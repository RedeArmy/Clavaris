package com.clavaris.identity.application.usecase.verifytotpchallenge;

import static org.mockito.Mockito.mock;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.codelockout.CodeAttemptTracker;
import com.clavaris.identity.application.usecase.codelockout.CodeLockout;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.application.usecase.starttotpenrollment.TotpCredentialRepository;
import com.clavaris.identity.application.usecase.starttotpenrollment.TotpRecoveryCodeRepository;
import com.clavaris.identity.application.usecase.starttotpenrollment.TotpSecretCipher;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.TotpCredential;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.transaction.support.TransactionOperations;

/**
 * In-memory stand-ins for the authenticator-app ports, shared by the use case tests so each one
 * exercises the real rules (replay, single-use, lockout) and not a stubbed answer.
 */
public final class TotpTestSupport {

  public static final TransactionOperations NO_TRANSACTION =
      TransactionOperations.withoutTransaction();

  private TotpTestSupport() {
    // Fixtures only.
  }

  /** The "encryption" the tests use: reversible, and visibly not the plain secret. */
  /** Binds a secret to its Account the way the real cipher does, so a copied one fails to read. */
  public static final class FakeCipher implements TotpSecretCipher {
    @Override
    public String encrypt(final AccountId accountId, final String plainSecret) {
      return "enc:" + accountId.value() + ":" + plainSecret;
    }

    @Override
    public String decrypt(final AccountId accountId, final String encryptedSecret) {
      final String prefix = "enc:" + accountId.value() + ":";
      if (!encryptedSecret.startsWith(prefix)) {
        throw new IllegalStateException("AES-GCM decryption failed");
      }
      return encryptedSecret.substring(prefix.length());
    }
  }

  public static final class InMemoryCredentials implements TotpCredentialRepository {
    public final Map<AccountId, TotpCredential> byAccount = new HashMap<>();

    @Override
    public Optional<TotpCredential> findByAccountId(final AccountId accountId) {
      return Optional.ofNullable(byAccount.get(accountId));
    }

    @Override
    public void save(final TotpCredential credential) {
      byAccount.put(credential.accountId(), credential);
    }

    @Override
    public boolean existsConfirmed(final AccountId accountId) {
      return findByAccountId(accountId).filter(TotpCredential::isConfirmed).isPresent();
    }

    @Override
    public boolean spendStep(final AccountId accountId, final long step) {
      final TotpCredential credential = byAccount.get(accountId);
      if (credential == null || !credential.accepts(step)) {
        return false;
      }
      credential.spend(step);
      return true;
    }

    @Override
    public boolean deleteByAccountId(final AccountId accountId) {
      return byAccount.remove(accountId) != null;
    }
  }

  public static final class InMemoryRecoveryCodes implements TotpRecoveryCodeRepository {
    public final Map<AccountId, Set<String>> unused = new HashMap<>();

    @Override
    public void replaceAll(
        final AccountId accountId, final OrganizationId organizationId, final List<String> hashes) {
      unused.put(accountId, new HashSet<>(hashes));
    }

    @Override
    public boolean consume(final AccountId accountId, final String codeHash) {
      return unused.getOrDefault(accountId, new HashSet<>()).remove(codeHash);
    }

    @Override
    public long countUnused(final AccountId accountId) {
      return unused.getOrDefault(accountId, Set.of()).size();
    }

    @Override
    public void deleteAll(final AccountId accountId) {
      unused.remove(accountId);
    }
  }

  /** Counts wrong entries per key and locks after {@code limit} of them. */
  public static final class CountingTracker implements CodeAttemptTracker {
    private final Map<String, Integer> failures = new HashMap<>();
    private final Set<String> locked = new HashSet<>();
    private final int limit;

    public CountingTracker(final int limit) {
      this.limit = limit;
    }

    @Override
    public boolean isLocked(final String key) {
      return locked.contains(key);
    }

    @Override
    public Failure recordFailure(final String key) {
      final int count = failures.merge(key, 1, Integer::sum);
      if (count >= limit) {
        locked.add(key);
        return new Failure(true, Duration.ofMinutes(15));
      }
      return Failure.counted();
    }

    @Override
    public void clear(final String key) {
      failures.remove(key);
      locked.remove(key);
    }
  }

  public static CodeLockout lockout(final CodeAttemptTracker tracker) {
    // Nothing is announced in these tests: the executor runs inline and the account is unknown.
    final AccountRepository accounts = mock(AccountRepository.class);
    return new CodeLockout(
        tracker, accounts, mock(MailSender.class), mock(AuditEventRecorder.class), Runnable::run);
  }

  public static List<String> noRecorded() {
    return new ArrayList<>();
  }
}
