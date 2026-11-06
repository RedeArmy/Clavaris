package com.clavaris.identity.application.usecase.starttotpenrollment;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.TotpCredential;
import com.clavaris.identity.domain.service.Base32;
import com.clavaris.identity.domain.service.TotpAlgorithm;
import org.springframework.transaction.support.TransactionOperations;

/**
 * ADR-0040. The secret is generated here, stored encrypted and unconfirmed, and returned once. An
 * unconfirmed one left by an abandoned setup is simply replaced: it was never asked for at sign-in,
 * so nothing depends on it.
 */
public class StartTotpEnrollmentService implements StartTotpEnrollmentUseCase {

  private final AccountRepository accounts;
  private final AccountAuthenticationPolicyProvider policyProvider;
  private final TotpCredentialRepository credentials;
  private final TotpSecretCipher cipher;
  private final TransactionOperations transaction;

  public StartTotpEnrollmentService(
      final AccountRepository accounts,
      final AccountAuthenticationPolicyProvider policyProvider,
      final TotpCredentialRepository credentials,
      final TotpSecretCipher cipher,
      final TransactionOperations transaction) {
    this.accounts = accounts;
    this.policyProvider = policyProvider;
    this.credentials = credentials;
    this.cipher = cipher;
    this.transaction = transaction;
  }

  @Override
  public TotpEnrollmentStart handle(final StartTotpEnrollmentCommand command) {
    final Account account =
        accounts.findById(command.accountId()).orElseThrow(TotpNotEnabledException::new);
    if (!policyProvider.policyFor(account.organizationId()).totpEnabled()) {
      throw new TotpNotEnabledException();
    }
    if (credentials.existsConfirmed(account.id())) {
      throw new TotpAlreadyEnrolledException();
    }

    final String secret = Base32.encode(TotpAlgorithm.newSecret());
    final TotpCredential pending =
        TotpCredential.pending(
            account.id(), account.organizationId(), cipher.encrypt(account.id(), secret));
    transaction.executeWithoutResult(
        status -> {
          credentials.deleteByAccountId(account.id());
          credentials.save(pending);
        });
    return new TotpEnrollmentStart(
        groups(secret),
        TotpAlgorithm.otpauthUri(command.issuer(), account.email().value(), secret));
  }

  // Groups of four are easier to read aloud and to type; the decoder ignores the spaces.
  private static String groups(final String secret) {
    final StringBuilder out = new StringBuilder(secret.length() + secret.length() / 4);
    for (int i = 0; i < secret.length(); i += 4) {
      if (i > 0) {
        out.append(' ');
      }
      out.append(secret, i, Math.min(i + 4, secret.length()));
    }
    return out.toString();
  }
}
