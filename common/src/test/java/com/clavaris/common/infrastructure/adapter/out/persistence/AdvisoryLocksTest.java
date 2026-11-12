package com.clavaris.common.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * TD-ARCH-055, against a real PostgreSQL: a lock is shared exactly when family and identifier are
 * both the same, and two unrelated keys that a 32-bit hash would have merged stay apart.
 */
@Testcontainers
class AdvisoryLocksTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  private static PGSimpleDataSource dataSource;

  @BeforeAll
  static void setUpDataSource() {
    dataSource = new PGSimpleDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
  }

  /** Whether a second transaction could take the lock {@code key} stands for right now. */
  private static boolean canTake(final Connection other, final String key) throws SQLException {
    try (PreparedStatement statement =
        other.prepareStatement("SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))")) {
      statement.setString(1, key);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getBoolean(1);
      }
    }
  }

  /** Holds the helper's lock in an open transaction, then asks the callback what a rival sees. */
  private static void whileHolding(
      final String family, final Object identifier, final RivalCheck check) throws Exception {
    try (Connection holder = dataSource.getConnection();
        Connection rival = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      rival.setAutoCommit(false);
      new JdbcTemplate(new SingleConnectionDataSource(holder, true))
          .query(
              AdvisoryLocks.TRANSACTION_LOCK_SQL,
              resultSet -> {
                /* the lock is the point */
              },
              AdvisoryLocks.key(family, identifier));
      check.run(rival);
      rival.rollback();
      holder.rollback();
    }
  }

  @FunctionalInterface
  private interface RivalCheck {
    void run(Connection rival) throws Exception;
  }

  @Test
  void theSameFamilyAndIdentifierIsOneLock() throws Exception {
    final UUID id = UUID.randomUUID();

    whileHolding(
        AdvisoryLocks.WORKSPACE_ROLE_CHANGE,
        id,
        rival ->
            assertThat(canTake(rival, AdvisoryLocks.key(AdvisoryLocks.WORKSPACE_ROLE_CHANGE, id)))
                .as("a second transaction on the same Workspace must wait")
                .isFalse());
  }

  @Test
  void twoFamiliesNeverShareALockEvenForTheSameIdentifier() throws Exception {
    // Webhook registration and signing-key rotation both used the Organization's bare UUID, so
    // registering an endpoint waited for an unrelated key rotation of the same Organization.
    final UUID organizationId = UUID.randomUUID();

    whileHolding(
        AdvisoryLocks.SIGNING_KEY_ROTATION,
        organizationId,
        rival ->
            assertThat(
                    canTake(
                        rival,
                        AdvisoryLocks.key(
                            AdvisoryLocks.WEBHOOK_ENDPOINT_REGISTRATION, organizationId)))
                .as("a different family on the same identifier is a different lock")
                .isTrue());
  }

  @Test
  void twoKeysThatA32BitHashMergesStayApart() throws Exception {
    // Among 300,000 numbers PostgreSQL's 32-bit hashtext collides for some pair, which is exactly
    // what the old scheme would have turned into one lock shared by two tenants.
    final List<String> pair = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT (array_agg(n::text))[1], (array_agg(n::text))[2] "
                    + "FROM generate_series(1, 300000) n GROUP BY hashtext(n::text) "
                    + "HAVING count(*) > 1 LIMIT 1");
        ResultSet resultSet = statement.executeQuery()) {
      assertThat(resultSet.next()).as("a 32-bit collision exists in 300,000 keys").isTrue();
      pair.add(resultSet.getString(1));
      pair.add(resultSet.getString(2));
    }
    assertThat(pair.get(0)).isNotEqualTo(pair.get(1));

    whileHolding(
        AdvisoryLocks.WORKSPACE_ROLE_CHANGE,
        pair.get(0),
        rival ->
            assertThat(
                    canTake(
                        rival, AdvisoryLocks.key(AdvisoryLocks.WORKSPACE_ROLE_CHANGE, pair.get(1))))
                .as(
                    "keys %s and %s collide at 32 bits but must not share a lock",
                    pair.get(0), pair.get(1))
                .isTrue());
  }

  @Test
  void theKeyIsTheFamilyAndTheIdentifier() {
    assertThat(AdvisoryLocks.key("family", "x")).isEqualTo("family:x");
    assertThat(AdvisoryLocks.key("family", 42)).isEqualTo("family:42");
  }
}
