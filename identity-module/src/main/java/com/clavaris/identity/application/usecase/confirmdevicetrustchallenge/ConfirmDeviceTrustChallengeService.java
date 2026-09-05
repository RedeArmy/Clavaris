package com.clavaris.identity.application.usecase.confirmdevicetrustchallenge;

import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
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

  public ConfirmDeviceTrustChallengeService(final VerificationTokenRepository tokens) {
    this.tokens = tokens;
  }

  @Override
  @Transactional
  @SuppressWarnings("PMD.GuardLogStatement")
  public void handle(final ConfirmDeviceTrustChallengeCommand command) {
    final String presentedHash = RefreshTokenSecret.hash(command.presentedRawCode());
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
    token.consume();
    tokens.save(token);
    LOG.info("event=device_trust_challenge_success accountId={}", command.accountId());
  }
}
