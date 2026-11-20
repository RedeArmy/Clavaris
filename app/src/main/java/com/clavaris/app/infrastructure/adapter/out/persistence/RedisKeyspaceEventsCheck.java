package com.clavaris.app.infrastructure.adapter.out.persistence;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * TD-ARCH-062 / ADR-0045 §1: the sessions Redis has to carry {@code notify-keyspace-events} with at
 * least {@code Egx} (keyevent notifications for generic commands and expiry), because Spring
 * Session's indexed repository hears a session expire or be deleted through them. The application
 * no longer sets it at start-up, since a failing {@code CONFIG SET} used to end the process, so a
 * Redis that is run elsewhere, or bought managed, without it keeps working and silently stops
 * announcing expired and deleted sessions. This reads the setting, at start-up and every few
 * minutes, and says which of three things is true:
 *
 * <ul>
 *   <li>{@link State#CONFIGURED} (gauge 1): the flags are there;
 *   <li>{@link State#MISSING} (gauge 0): Redis answered and they are not, which is what the {@code
 *       RedisKeyspaceEventsMissing} alert pages on;
 *   <li>{@link State#UNKNOWN} (gauge -1): it could not be read, because Redis is down or because
 *       {@code CONFIG} is disabled on a managed service. That is not a failure and never alerts: a
 *       service that forbids {@code CONFIG} is exactly the case ADR-0045 made possible to run.
 * </ul>
 *
 * Never blocks nor fails the application: every error is an {@code UNKNOWN}.
 */
@Component
class RedisKeyspaceEventsCheck {

  private static final Logger LOG = LoggerFactory.getLogger(RedisKeyspaceEventsCheck.class);

  /* package */ static final String SETTING = "notify-keyspace-events";

  /** What the check concluded; the gauge reads {@link #gauge()}. */
  /* package */ enum State {
    CONFIGURED(1),
    MISSING(0),
    UNKNOWN(-1);

    private final int gauge;

    State(final int gauge) {
      this.gauge = gauge;
    }

    /* package */ int gauge() {
      return gauge;
    }
  }

  private final RedisConnectionFactory connectionFactory;
  // Held in a field: a gauge keeps only a weak reference to what it reads.
  private final AtomicInteger gauge = new AtomicInteger(State.UNKNOWN.gauge());
  private volatile State last = State.UNKNOWN;

  /* package */ RedisKeyspaceEventsCheck(
      final RedisConnectionFactory connectionFactory, final MeterRegistry meters) {
    this.connectionFactory = connectionFactory;
    meters.gauge("clavaris.redis.keyspace_events.configured", gauge);
  }

  // The first run waits a little so start-up is not slowed by it, then it repeats; the initial
  // delay
  // is a property only so a test can keep it from running in the background.
  @Scheduled(
      initialDelayString = "${clavaris.redis.keyspace-check.initial-delay-ms:30000}",
      fixedDelayString = "${clavaris.redis.keyspace-check.interval-ms:300000}")
  /* package */ State check() {
    final State now = read();
    gauge.set(now.gauge());
    if (now == State.MISSING && last != State.MISSING) {
      // The setting's own value is a set of flags, never a secret or a personal value.
      LOG.warn(
          "event=redis_keyspace_events_missing setting={} required=Egx"
              + " action=expired_and_deleted_sessions_are_not_announced",
          SETTING);
    } else if (now == State.CONFIGURED && last == State.MISSING) {
      LOG.info("event=redis_keyspace_events_restored");
    }
    last = now;
    return now;
  }

  // PMD.AvoidCatchingGenericException is not needed: every failure this can meet, Redis down, a
  // forbidden or renamed CONFIG, an answer that is not what was expected, reaches Spring Data as a
  // DataAccessException.
  private State read() {
    try (RedisConnection connection = connectionFactory.getConnection()) {
      final Properties config = connection.serverCommands().getConfig(SETTING);
      if (config == null || config.getProperty(SETTING) == null) {
        return State.UNKNOWN;
      }
      return carriesRequiredFlags(config.getProperty(SETTING)) ? State.CONFIGURED : State.MISSING;
    } catch (final DataAccessException _) {
      return State.UNKNOWN;
    }
  }

  /**
   * {@code E} (keyevent) plus generic ({@code g}) and expired ({@code x}) events; {@code A} is the
   * alias Redis uses for "all of the class flags", so it stands for both.
   */
  /* package */ static boolean carriesRequiredFlags(final String flags) {
    final boolean all = flags.indexOf('A') >= 0;
    return flags.indexOf('E') >= 0
        && (all || flags.indexOf('g') >= 0)
        && (all || flags.indexOf('x') >= 0);
  }
}
