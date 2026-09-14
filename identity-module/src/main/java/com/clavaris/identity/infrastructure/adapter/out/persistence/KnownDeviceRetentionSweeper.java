package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.ToLongFunction;
import org.slf4j.Logger;

/**
 * The age-based sweep-and-log decision {@link KnownDeviceRetentionJob}/{@link
 * PlatformKnownDeviceRetentionJob} both run — found live by SonarCloud's own duplication analysis
 * once TD-FUT-033's identical {@code PostgresAdvisoryJobLock} wiring landed in both, on top of an
 * already-near-identical pair ({@link PlatformKnownDeviceRetentionJob}'s own Javadoc already called
 * itself "platform-tier mirror of {@link KnownDeviceRetentionJob}" before that). Same "shared
 * sweep-and-log helper, each job still owns its own bean/table/schedule wiring" precedent {@code
 * EventOutboxRetentionSweeper} (common module) already establishes for an identical situation —
 * cross-module there, so package-private here is enough, both callers already live in this exact
 * package.
 *
 * <p>{@code deleteByLastSeenAtBefore} is a plain method reference ({@code
 * knownDevices::deleteByLastSeenAtBefore}), not a shared repository interface — {@link
 * SpringDataKnownDeviceJpaRepository}/{@link SpringDataPlatformKnownDeviceJpaRepository} are two
 * genuinely different Spring Data repositories over two different entity types; a {@code
 * ToLongFunction<Instant>} needs nothing more than the one method both already expose with the
 * identical signature. {@code ToLongFunction} over {@code Function<Instant, Long>} (SonarCloud
 * finding, 2026-09-13): both repository methods already declare a primitive {@code long} return
 * type — the boxed {@code Function} forced an unboxing on every call this specialised interface
 * avoids.
 */
/* package */ final class KnownDeviceRetentionSweeper {

  private KnownDeviceRetentionSweeper() {}

  // PMD.LongVariable: deleteByLastSeenAtBefore names exactly what the method reference does —
  // same "deliberate, descriptive name over an arbitrary shortening" convention
  // DashboardControllerSupport's
  // own class-level suppression documents for an identical situation.
  @SuppressWarnings("PMD.LongVariable")
  /* package */ static void sweep(
      final Logger log,
      final String eventName,
      final ToLongFunction<Instant> deleteByLastSeenAtBefore,
      final int retentionDays) {
    final Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
    final long deleted = deleteByLastSeenAtBefore.applyAsLong(cutoff);
    if (deleted > 0) {
      log.info("event={} deletedCount={} retentionDays={}", eventName, deleted, retentionDays);
    }
  }
}
