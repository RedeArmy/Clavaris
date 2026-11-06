package com.clavaris.identity.infrastructure.adapter.out.crypto;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.common.infrastructure.adapter.out.crypto.StoredSecretReencryptor;
import com.clavaris.common.infrastructure.adapter.out.crypto.StoredSecretReencryptor.Result;
import com.clavaris.common.infrastructure.adapter.out.crypto.StoredSecretReencryptor.Target;
import com.clavaris.common.infrastructure.adapter.out.persistence.PostgresAdvisoryJobLock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * TD-SEC-089: rewrites every stored authenticator secret under the active key, so rotating the key
 * closes the incident instead of leaving the old key in the previous-keys list until every holder
 * sets a new secret. Runs a minute after startup (a rotation is a redeploy, so it completes with
 * it) and nightly after that, under an advisory lock so two instances never sweep together. Once
 * nothing is left under an old key, it can be removed from the previous-keys list
 * (deployment-runbook.md section 6h).
 */
@Component
class TotpSecretReencryptionJob {

  private static final Logger LOG = LoggerFactory.getLogger(TotpSecretReencryptionJob.class);

  private static final List<Target> TARGETS =
      List.of(
          new Target(
              "totp-secret",
              "totp_credentials",
              "secret_encrypted",
              "'totp_credentials.encrypted_secret/' || account_id"));

  private final AesGcmTotpSecretCipher cipher;
  private final PostgresAdvisoryJobLock jobLock;
  private final StoredSecretReencryptor reencryptor;

  // Constructed only by Spring's component scan.
  /* package */ TotpSecretReencryptionJob(
      final AesGcmTotpSecretCipher cipher,
      final JdbcTemplate jdbc,
      final PostgresAdvisoryJobLock jobLock,
      final ObjectProvider<SecurityMetricsRecorder> metrics,
      @Value("${clavaris.crypto.reencrypt-batch-size:500}") final int batchSize) {
    this.cipher = cipher;
    this.jobLock = jobLock;
    this.reencryptor =
        new StoredSecretReencryptor(
            jdbc, metrics.getIfAvailable(() -> (name, tags) -> {}), batchSize);
  }

  // 05:00, after the nightly sweeps (03:30 to 04:45); the second trigger is the one-off run that
  // lets a rotation finish with the deploy that introduced the new key.
  @Scheduled(cron = "0 0 5 * * *")
  @Scheduled(initialDelay = 60_000, fixedDelay = Long.MAX_VALUE)
  /* package */ void sweep() {
    jobLock.runIfLockAcquired("totp_secret_reencryption", LOG, this::sweepLocked);
  }

  /** Runs one sweep now; package-visible for the test. */
  /* package */ List<Result> sweepLocked() {
    return TARGETS.stream().map(target -> reencryptor.run(cipher.envelope(), target)).toList();
  }
}
