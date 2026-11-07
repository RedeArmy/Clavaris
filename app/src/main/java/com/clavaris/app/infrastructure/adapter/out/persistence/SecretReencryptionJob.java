package com.clavaris.app.infrastructure.adapter.out.persistence;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.common.infrastructure.adapter.out.crypto.SecretReencryptionStore;
import com.clavaris.common.infrastructure.adapter.out.crypto.StoredSecretReencryptor;
import com.clavaris.common.infrastructure.adapter.out.crypto.StoredSecretReencryptor.Result;
import com.clavaris.common.infrastructure.adapter.out.persistence.PostgresAdvisoryJobLock;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * TD-SEC-089: rewrites every at-rest secret under the active key of its store (authenticator
 * secrets, social client secrets, webhook signing secrets), so rotating a key closes the incident
 * instead of leaving the old key in the previous-keys list until every holder sets a new secret.
 * One job for all of them: each store states what it owns through {@link SecretReencryptionStore}.
 *
 * <p>Runs a minute after startup (a rotation is a redeploy, so it completes with it) and nightly
 * after that, each store under its own advisory lock so two instances never sweep the same one
 * together. Once nothing is left under an old key, that key can be removed from the previous-keys
 * list (deployment-runbook.md section 6h).
 */
@Component
class SecretReencryptionJob {

  private static final Logger LOG = LoggerFactory.getLogger(SecretReencryptionJob.class);

  private final List<SecretReencryptionStore> stores;
  private final PostgresAdvisoryJobLock jobLock;
  private final StoredSecretReencryptor reencryptor;

  // Constructed only by Spring's component scan.
  /* package */ SecretReencryptionJob(
      final List<SecretReencryptionStore> stores,
      final JdbcTemplate jdbc,
      final PostgresAdvisoryJobLock jobLock,
      final ObjectProvider<SecurityMetricsRecorder> metrics,
      @Value("${clavaris.crypto.reencrypt-batch-size:500}") final int batchSize) {
    this.stores = stores;
    this.jobLock = jobLock;
    this.reencryptor =
        new StoredSecretReencryptor(
            jdbc, metrics.getIfAvailable(() -> (name, tags) -> {}), batchSize);
  }

  // 05:00, after the nightly sweeps (03:30 to 04:45); the second trigger is the one-off run that
  // lets a rotation finish with the deploy that introduced the new key.
  @Scheduled(cron = "0 0 5 * * *")
  @Scheduled(
      initialDelayString = "${clavaris.crypto.reencrypt-initial-delay:PT1M}",
      fixedDelay = Long.MAX_VALUE)
  /* package */ void sweep() {
    for (final SecretReencryptionStore store : stores) {
      jobLock.runIfLockAcquired(
          "secret_reencryption:" + store.store(), LOG, () -> sweepStore(store));
    }
  }

  /** Runs one store's sweep now; package-visible for the test. */
  /* package */ List<Result> sweepStore(final SecretReencryptionStore store) {
    final List<Result> results = new ArrayList<>();
    store.targets().forEach(target -> results.add(reencryptor.run(store.envelope(), target)));
    return results;
  }
}
