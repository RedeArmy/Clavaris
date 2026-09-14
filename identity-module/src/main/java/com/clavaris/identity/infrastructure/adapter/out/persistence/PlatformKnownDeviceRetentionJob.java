package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.common.infrastructure.adapter.out.persistence.PostgresAdvisoryJobLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * TD-FUT-026 (closed 2026-09-02): platform-tier mirror of {@link KnownDeviceRetentionJob} — same
 * age-based retention sweep, same {@code last_seen_at} (not {@code first_seen_at}) aging rule, same
 * {@code PlatformDeviceCookie}-tied 400-day default; see that class's own Javadoc for the full
 * rationale, unchanged here beyond the table/repository it sweeps.
 */
@Component
class PlatformKnownDeviceRetentionJob {

  private static final Logger LOG = LoggerFactory.getLogger(PlatformKnownDeviceRetentionJob.class);

  private final SpringDataPlatformKnownDeviceJpaRepository knownDevices;
  private final int retentionDays;
  private final PostgresAdvisoryJobLock jobLock;

  /* package */ PlatformKnownDeviceRetentionJob(
      final SpringDataPlatformKnownDeviceJpaRepository knownDevices,
      @Value("${clavaris.platform-known-device.retention-days:400}") final int retentionDays,
      final PostgresAdvisoryJobLock jobLock) {
    this.knownDevices = knownDevices;
    this.retentionDays = retentionDays;
    this.jobLock = jobLock;
  }

  // 04:15 — staggered one slot after KnownDeviceRetentionJob's own 04:00, same "don't collide"
  // reasoning every scheduled job in this codebase already documents.
  //
  // TD-FUT-033: guarded by PostgresAdvisoryJobLock — see KnownDeviceRetentionJob's own identical
  // shape for why @Transactional stays on this externally-invoked method, not the private one it
  // delegates to (Spring AOP self-invocation).
  @Scheduled(cron = "0 15 4 * * *")
  @Transactional
  /* package */ void sweepStaleDevices() {
    jobLock.runIfLockAcquired(
        "platform_known_device_retention", LOG, this::sweepStaleDevicesLocked);
  }

  private void sweepStaleDevicesLocked() {
    KnownDeviceRetentionSweeper.sweep(
        LOG,
        "platform_known_device_retention_swept",
        knownDevices::deleteByLastSeenAtBefore,
        retentionDays);
  }
}
