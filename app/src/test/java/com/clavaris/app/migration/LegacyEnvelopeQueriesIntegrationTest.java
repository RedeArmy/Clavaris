package com.clavaris.app.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.common.infrastructure.adapter.out.crypto.AesGcmCipher;
import com.clavaris.common.infrastructure.adapter.out.crypto.EnvelopeCipher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * TD-SEC-090: {@code scripts/host/legacy-envelope-queries.sql} is what an operator runs to learn
 * whether {@code CRYPTO_ACCEPT_LEGACY_ENVELOPE} can be switched off, so it has to be right on the
 * real schema and agree with {@link EnvelopeCipher#isLegacy}. The test applies every migration,
 * writes values with the real ciphers (the envelope format and the older one), runs the file as it
 * is, and checks the count per store. A renamed column, a new store the file forgot, or a change of
 * the format fails here, in a build, and not when someone flips the flag in production and a user's
 * authenticator stops working.
 */
@Testcontainers
class LegacyEnvelopeQueriesIntegrationTest {

  private static final String KEY = key();
  private static final EnvelopeCipher ENVELOPE = new EnvelopeCipher("totp-secret", "1", KEY, "");
  private static final AesGcmCipher OLD_FORMAT = new AesGcmCipher(KEY);

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  private static String key() {
    final byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }

  @BeforeAll
  static void migrate() throws SQLException {
    try (Connection connection = connection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm SCHEMA public");
      statement.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto SCHEMA public");
    }
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
        .load()
        .migrate();
  }

  @BeforeEach
  void emptyTheThreeStores() throws SQLException {
    try (Connection connection = connection();
        Statement statement = connection.createStatement()) {
      statement.execute("SET session_replication_role = replica");
      statement.execute("DELETE FROM totp_credentials");
      statement.execute("DELETE FROM webhook_endpoints");
      statement.execute("DELETE FROM organization_social_credentials");
    }
  }

  private static Connection connection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  /**
   * The file is found from wherever the build runs: module directory, repository root or an IDE.
   */
  private static String queries() throws IOException {
    Path directory = Path.of("").toAbsolutePath();
    while (directory != null) {
      final Path candidate = directory.resolve("scripts/host/legacy-envelope-queries.sql");
      if (Files.exists(candidate)) {
        return Files.readString(candidate);
      }
      directory = directory.getParent();
    }
    throw new IOException(
        "scripts/host/legacy-envelope-queries.sql not found above the working directory");
  }

  private static Map<String, Integer> countPerStore() throws SQLException, IOException {
    final Map<String, Integer> counts = new LinkedHashMap<>();
    try (Connection connection = connection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(queries())) {
      while (rows.next()) {
        counts.put(rows.getString("store"), rows.getInt("legacy_values"));
      }
    }
    return counts;
  }

  private static int legacyIn(final String store) throws SQLException, IOException {
    return countPerStore().entrySet().stream()
        .filter(entry -> entry.getKey().startsWith(store))
        .mapToInt(Map.Entry::getValue)
        .sum();
  }

  private static String current(final String context) {
    return ENVELOPE.encrypt("secret", context);
  }

  private static String legacy() {
    return OLD_FORMAT.encrypt("secret");
  }

  private static void totp(final String stored) throws SQLException {
    insert(
        "INSERT INTO totp_credentials (account_id, organization_id, secret_encrypted) VALUES (?, ?, ?)",
        UUID.randomUUID(),
        UUID.randomUUID(),
        stored);
  }

  private static void webhook(final String current, final String previous) throws SQLException {
    insert(
        "INSERT INTO webhook_endpoints (id, organization_id, url, subscribed_event_types,"
            + " current_secret_encrypted, previous_secret_encrypted)"
            + " VALUES (?, ?, 'https://example.com/hook', '[\"x\"]', ?, ?)",
        UUID.randomUUID(),
        UUID.randomUUID(),
        current,
        previous);
  }

  private static void social(final String stored, final String provider) throws SQLException {
    insert(
        "INSERT INTO organization_social_credentials (organization_id, provider, client_id,"
            + " client_secret_encrypted) VALUES (?, ?, 'client', ?)",
        UUID.randomUUID(),
        provider,
        stored);
  }

  private static void insert(final String sql, final Object... values) throws SQLException {
    try (Connection connection = connection();
        Statement mode = connection.createStatement()) {
      // The foreign keys point at rows of other modules; only the secret column matters here.
      mode.execute("SET session_replication_role = replica");
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        for (int i = 0; i < values.length; i++) {
          statement.setObject(i + 1, values[i]);
        }
        statement.executeUpdate();
      }
    }
  }

  @Test
  void theQueriesAgreeWithTheCipherAboutWhatIsLegacy() {
    assertThat(ENVELOPE.isLegacy(legacy())).as("a value of the old format").isTrue();
    assertThat(ENVELOPE.isLegacy(current("row"))).as("a value of the envelope format").isFalse();
  }

  @Test
  void anEmptyDatabaseHasNoLegacyValueInAnyStoreAndEveryStoreIsListed() throws Exception {
    final Map<String, Integer> counts = countPerStore();

    assertThat(counts).hasSize(3);
    assertThat(counts.values()).containsOnly(0);
  }

  @Test
  void aTotpSecretIsCountedOnlyInTheOldFormat() throws Exception {
    totp(current("totp"));
    assertThat(legacyIn("totp_credentials")).isZero();

    totp(legacy());
    assertThat(legacyIn("totp_credentials")).isEqualTo(1);
    assertThat(legacyIn("webhook_endpoints")).isZero();
    assertThat(legacyIn("organization_social_credentials")).isZero();
  }

  @Test
  void aWebhookEndpointIsCountedWhenEitherOfItsTwoSecretsIsOld() throws Exception {
    webhook(current("a"), null);
    webhook(current("b"), current("b-previous"));
    assertThat(legacyIn("webhook_endpoints")).as("both under the envelope").isZero();

    webhook(legacy(), null);
    webhook(current("c"), legacy());
    webhook(legacy(), legacy());
    assertThat(legacyIn("webhook_endpoints"))
        .as("old current, old previous, and both: one endpoint each")
        .isEqualTo(3);
  }

  @Test
  void aSocialCredentialIsCountedOnlyInTheOldFormat() throws Exception {
    social(current("google"), "GOOGLE");
    assertThat(legacyIn("organization_social_credentials")).isZero();

    social(legacy(), "GITHUB");
    assertThat(legacyIn("organization_social_credentials")).isEqualTo(1);
  }

  @Test
  void onceTheLastOldValueIsRewrittenTheTotalIsZero() throws Exception {
    totp(legacy());
    webhook(current("a"), legacy());
    social(legacy(), "GOOGLE");
    assertThat(countPerStore().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(3);

    try (Connection connection = connection();
        Statement statement = connection.createStatement()) {
      statement.execute("UPDATE totp_credentials SET secret_encrypted = 'v1.1.rewritten'");
      statement.execute(
          "UPDATE webhook_endpoints SET previous_secret_encrypted = 'v1.1.rewritten'");
      statement.execute(
          "UPDATE organization_social_credentials SET client_secret_encrypted = 'v1.1.rewritten'");
    }

    assertThat(countPerStore().values()).containsOnly(0);
  }
}
