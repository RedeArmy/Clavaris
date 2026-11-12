package com.clavaris.common.infrastructure.adapter.out.persistence;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * TD-ARCH-055: the one place that decides how a string becomes a PostgreSQL advisory-lock key.
 *
 * <p>Seven sites used {@code hashtext(?)}, a 32-bit hash, on a bare identifier. With tens of
 * thousands of Workspaces and endpoints two keys will share a lock sooner or later (the birthday
 * bound is about 77,000 keys for an even chance), and the effect is needless waiting between two
 * tenants' unrelated operations, or a job tick skipped because a transaction lock happens to hold
 * its key. Worse, the keys were bare UUIDs with no family: webhook endpoint registration and
 * signing key rotation for the same Organization took the same lock.
 *
 * <p>Now every key is {@code family:identifier} and goes through {@code hashtextextended(key, 0)},
 * a 64-bit hash, so a collision between unrelated keys is out of reach and two families never share
 * a lock by construction. The keys differ from the old ones, which matters only if an old and a new
 * instance run at once (a rolling deploy): this deployment replaces its single application
 * container, so they never do.
 */
public final class AdvisoryLocks {

  /** Webhook endpoint registration, one lock per Organization (the registration cap check). */
  public static final String WEBHOOK_ENDPOINT_REGISTRATION = "webhook-endpoint-registration";

  /** Webhook endpoint secret rotation, one lock per endpoint. */
  public static final String WEBHOOK_SECRET_ROTATION = "webhook-secret-rotation";

  /** Role changes of a Workspace's members, one lock per Workspace. */
  public static final String WORKSPACE_ROLE_CHANGE = "workspace-role-change";

  /** Roles of a Workspace team, one lock per Workspace and role. */
  public static final String WORKSPACE_TEAM_ROLE_CHANGE = "workspace-team-role-change";

  /** Signing key activation and rotation, one lock per Organization. */
  public static final String SIGNING_KEY_ROTATION = "signing-key-rotation";

  /** Adding to an Organization's access-restriction lists, one lock per Organization (the cap). */
  public static final String ACCESS_RESTRICTION_ENTRIES = "access-restriction-entries";

  /** A scheduled job's single-instance guard, one lock per job name. */
  public static final String SCHEDULED_JOB = "scheduled-job";

  /** Transaction-scoped: held until the surrounding transaction ends. */
  /* default */ static final String TRANSACTION_LOCK_SQL =
      "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))";

  /** Session-scoped and non-blocking: the caller must unlock on the same connection. */
  public static final String TRY_SESSION_LOCK_SQL =
      "SELECT pg_try_advisory_lock(hashtextextended(?, 0))";

  public static final String SESSION_UNLOCK_SQL =
      "SELECT pg_advisory_unlock(hashtextextended(?, 0))";

  private AdvisoryLocks() {
    // Static helper only — no instance state.
  }

  /** {@code family:identifier}, the text that is hashed. */
  public static String key(final String family, final Object identifier) {
    return family + ":" + identifier;
  }

  /**
   * Takes the transaction-scoped lock for {@code family} and {@code identifier}, waiting for any
   * other transaction holding it; released at commit or rollback.
   */
  public static void lockForTransaction(
      final JdbcTemplate jdbcTemplate, final String family, final Object identifier) {
    jdbcTemplate.query(
        TRANSACTION_LOCK_SQL,
        resultSet -> {
          /* side-effecting call — the lock itself is the point, not this row */
        },
        key(family, identifier));
  }
}
