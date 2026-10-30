package com.clavaris.identity.application.usecase.confirmdevicetrustchallenge;

import com.clavaris.identity.application.usecase.codelockout.CodeLockout;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.EmailOneTimeCode;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link ConfirmDeviceTrustChallengeUseCase} — ADR-0024 §6. Does not itself
 * establish a session or record the device as known: {@code DeviceTrustChallengeController} does
 * both, using the very same primary-factor establish call the original sign-in attempt deferred —
 * this use case's only job is validating the presented code.
 */
public class ConfirmDeviceTrustChallengeService implements ConfirmDeviceTrustChallengeUseCase {

  private static final Logger LOG =
      LoggerFactory.getLogger(ConfirmDeviceTrustChallengeService.class);

  private final VerificationTokenRepository tokens;
  private final CodeLockout lockout;

  public ConfirmDeviceTrustChallengeService(
      final VerificationTokenRepository tokens, final CodeLockout lockout) {
    this.tokens = tokens;
    this.lockout = lockout;
  }

  @Override
  @Transactional
  public void handle(final ConfirmDeviceTrustChallengeCommand command) {
    // ADR-0033: this 6-digit code had no limit on attempts at all. Too many wrong ones in a row
    // pause the verification for the Account; the password step already passed, so the person can
    // simply try again later.
    final String key = CodeLockout.deviceCodeKey(command.accountId());
    lockout.ensureOpen(key);
    try {
      confirm(command);
    } catch (final InvalidDeviceTrustChallengeException wrong) {
      lockout.onDeviceCodeFailure(command.accountId(), key);
      throw wrong;
    }
    lockout.onSuccess(key);
  }

  private void confirm(final ConfirmDeviceTrustChallengeCommand command) {
    final String presentedHash =
        EmailOneTimeCode.hashFor(command.accountId(), command.presentedRawCode());
    final Optional<VerificationToken> found = tokens.findByTokenHash(presentedHash);
    if (found.isEmpty()
        || found.get().type() != VerificationTokenType.DEVICE_TRUST_CHALLENGE
        || !found.get().isActive()
        || !found.get().accountId().equals(command.accountId())) {
      LOG.info(
          "event=device_trust_challenge_failure accountId={} reason=invalid_or_expired_code",
          command.accountId());
      throw new InvalidDeviceTrustChallengeException();
    }

    final VerificationToken token = found.get();
    // The atomic single-use check: a plain consume() then save() let two submissions of the same
    // code both pass (see AuthenticateWithEmailCodeService).
    if (!tokens.consumeIfActive(token.id(), Instant.now())) {
      LOG.info(
          "event=device_trust_challenge_failure accountId={} reason=invalid_or_expired_code",
          command.accountId());
      throw new InvalidDeviceTrustChallengeException();
    }
    LOG.info("event=device_trust_challenge_success accountId={}", command.accountId());
  }
}
