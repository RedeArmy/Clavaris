package com.clavaris.identity.application.usecase.verifytotpchallenge;

import org.springframework.transaction.support.TransactionOperations;

/**
 * The transaction is only around the check, which spends a step or a recovery code in a single
 * statement; no hash or network call sits inside it.
 */
public class VerifyTotpChallengeService implements VerifyTotpChallengeUseCase {

  private final TotpCodeVerifier verifier;
  private final TransactionOperations transaction;

  public VerifyTotpChallengeService(
      final TotpCodeVerifier verifier, final TransactionOperations transaction) {
    this.verifier = verifier;
    this.transaction = transaction;
  }

  @Override
  public TotpMethod handle(final VerifyTotpChallengeCommand command) {
    return transaction.execute(status -> verifier.verify(command.accountId(), command.code()));
  }
}
