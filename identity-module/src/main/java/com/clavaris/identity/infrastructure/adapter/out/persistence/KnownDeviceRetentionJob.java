package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.common.infrastructure.adapter.out.persistence.PostgresAdvisoryJobLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * TD-PERF-002: bounds {@code known_devices} growth with an age-based retention sweep — the table
 * had no cleanup job at all before this, and every {@link com.clavaris.identity.domain.model.
 * KnownDevice} row a real login ever mints would otherwise accumulate forever, one per device per
 * account.
 *
 * <p><b>Why {@code retentionDays} defaults to 400, not a rounder number:</b> tied directly to
 * {@code DeviceCookie}'s own 365-day {@code Max-Age}, plus a deliberate buffer — a row whose
 * corresponding cookie has already expired client-side can never be recognized again by {@code
 * RecordAccountLoginDeviceService} (there's no cookie left to hash and match), so keeping it past
 * that point serves no purpose; the extra ~35 days absorbs clock skew and the fact that a
 * long-lived-but-not-yet-renewed cookie's real client-side expiry is measured from when it was
 * *set*, not from this job's own last sweep. Age is {@code last_seen_at} (touched on every
 * recognized login), not {@code first_seen_at} — a device still in active, regular use must never
 * be swept just because it's old; only one that has genuinely gone quiet for the full window is a
 * candidate.
 *
 * <p>Sweeping a row here is not a security-relevant event — same "known device" experience as if
 * the cookie had simply been cleared: the next login from that same browser is treated as new,
 * BR-ID-14's notification fires again, nothing is lost except the (by definition, long-stale)
 * bookkeeping row itself.
 */
@Component
class KnownDeviceRetentionJob {

  private static final Logger LOG = LoggerFactory.getLogger(KnownDeviceRetentionJob.class);

  private final SpringDataKnownDeviceJpaRepository knownDevices;
  private final int retentionDays;
  private final PostgresAdvisoryJobLock jobLock;

  // Constructed only by Spring's own component scan (via @Component above).
  /* package */ KnownDeviceRetentionJob(
      final SpringDataKnownDeviceJpaRepository knownDevices,
      @Value("${clavaris.known-device.retention-days:400}") final int retentionDays,
      final PostgresAdvisoryJobLock jobLock) {
    this.knownDevices = knownDevices;
    this.retentionDays = retentionDays;
    this.jobLock = jobLock;
  }

  // Daily, off-peak (04:00 server time) — staggered after EventOutboxRetentionJob's own 03:30 slot
  // and AccountAuthMethodIntegrityCheckJob's own 03:45 slot, same "no other scheduled job to
  // coordinate against yet, just don't collide" reasoning those jobs' own Javadoc already
  // documents.
  //
  // TD-FUT-033: guarded by PostgresAdvisoryJobLock — see that class's own Javadoc. @Transactional
  // stays on THIS method, not the private one it delegates to below — Spring's proxy only
  // intercepts an externally-invoked call (the scheduler calling this bean through its proxy is
  // exactly that), never a self-invocation via `this::`, so a private method invoked that way
  // would silently run with no transaction at all if @Transactional were moved onto it instead.
  // The self-invoked private method still correctly participates in the transaction this method
  // opens (Spring's transaction binding is thread-local, not proxy-call-based) — it just can't
  // open a NEW one of its own via its own annotation, which it doesn't need to.
  @Scheduled(cron = "0 0 4 * * *")
  @Transactional
  /* package */ void sweepStaleDevices() {
    jobLock.runIfLockAcquired("known_device_retention", LOG, this::sweepStaleDevicesLocked);
  }

  private void sweepStaleDevicesLocked() {
    KnownDeviceRetentionSweeper.sweep(
        LOG, "known_device_retention_swept", knownDevices::deleteByLastSeenAtBefore, retentionDays);
  }
}
