package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

  /* package */ PlatformKnownDeviceRetentionJob(
      final SpringDataPlatformKnownDeviceJpaRepository knownDevices,
      @Value("${clavaris.platform-known-device.retention-days:400}") final int retentionDays) {
    this.knownDevices = knownDevices;
    this.retentionDays = retentionDays;
  }

  // 04:15 — staggered one slot after KnownDeviceRetentionJob's own 04:00, same "don't collide"
  // reasoning every scheduled job in this codebase already documents.
  @Scheduled(cron = "0 15 4 * * *")
  @Transactional
  /* package */ void sweepStaleDevices() {
    final Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
    final long deleted = knownDevices.deleteByLastSeenAtBefore(cutoff);
    if (deleted > 0) {
      LOG.info(
          "event=platform_known_device_retention_swept deletedCount={} retentionDays={}",
          deleted,
          retentionDays);
    }
  }
}
