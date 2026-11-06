package com.clavaris.common.infrastructure.adapter.out.crypto;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * TD-SEC-089: rewrites every stored secret that is not yet under the active key, so rotating a key
 * closes the incident once this has run, instead of leaving the old key in {@code *_PREVIOUS_KEYS}
 * until every holder happens to set a new secret. Also brings a value written before the envelope
 * format (no row binding) into it.
 *
 * <p>Each row is rewritten with {@code UPDATE ... WHERE id = ? AND column = <what was read>}: a
 * secret changed by a request while the sweep held the old text is left to that request, which
 * wrote it under the active key anyway. A value that cannot be read (a key missing from the ring, a
 * damaged value) is logged by its row id and skipped, never rewritten and never fatal to the sweep;
 * it stays counted until someone deals with it. Secrets and ciphertexts are never logged.
 */
public final class StoredSecretReencryptor {

  private static final Logger LOG = LoggerFactory.getLogger(StoredSecretReencryptor.class);

  private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");
  private static final Pattern CONTEXT_PREFIX = Pattern.compile("[A-Za-z0-9_./]+");

  private final JdbcTemplate jdbc;
  private final SecurityMetricsRecorder metrics;
  private final int batchSize;

  public StoredSecretReencryptor(
      final JdbcTemplate jdbc, final SecurityMetricsRecorder metrics, final int batchSize) {
    this.jdbc = jdbc;
    this.metrics = metrics;
    this.batchSize = batchSize;
  }

  /**
   * One encrypted column. The context of a row is {@code contextPrefix} followed by the listed
   * columns of that row joined with {@code /}: the text the store's cipher passes when it reads it.
   * Every part is checked to be a plain identifier or plain text, because they are put into the
   * statement as they are; they are constants of the calling store, never input.
   */
  public record Target(
      String store,
      String table,
      String column,
      String contextPrefix,
      List<String> contextColumns) {

    public Target {
      requireIdentifier(table);
      requireIdentifier(column);
      if (contextPrefix == null || !CONTEXT_PREFIX.matcher(contextPrefix).matches()) {
        throw new IllegalArgumentException("The context prefix must be plain text");
      }
      contextColumns = List.copyOf(contextColumns);
      contextColumns.forEach(Target::requireIdentifier);
    }

    private static void requireIdentifier(final String name) {
      if (name == null || !IDENTIFIER.matcher(name).matches()) {
        throw new IllegalArgumentException("Not a plain SQL identifier: " + name);
      }
    }

    /** The SQL expression that yields the context text of the row. */
    /* package */ String contextSql() {
      return "'" + contextPrefix + "' || " + String.join(" || '/' || ", contextColumns);
    }
  }

  public record Result(int rewritten, int unreadable) {}

  public Result run(final EnvelopeCipher cipher, final Target target) {
    final String select =
        "SELECT id, "
            + target.column()
            + " AS stored, "
            + target.contextSql()
            + " AS context FROM "
            + target.table()
            + " WHERE id > ? AND "
            + target.column()
            + " IS NOT NULL AND NOT starts_with("
            + target.column()
            + ", ?) ORDER BY id LIMIT ?";
    final String update =
        "UPDATE "
            + target.table()
            + " SET "
            + target.column()
            + " = ? WHERE id = ? AND "
            + target.column()
            + " = ?";
    int rewritten = 0;
    int unreadable = 0;
    // Keyset on id: a row that cannot be read stays selectable, so paging by OFFSET or by "still
    // not current" would return it forever.
    UUID after = new UUID(0L, 0L);
    List<Row> batch;
    do {
      batch =
          jdbc.query( // NOSONAR java:S2077 identifiers are validated by Target
              select,
              (rs, n) ->
                  new Row(
                      rs.getObject("id", UUID.class),
                      rs.getString("stored"),
                      rs.getString("context")),
              after,
              cipher.currentPrefix(),
              batchSize);
      for (final Row row : batch) {
        after = row.id();
        try {
          final String fresh = cipher.reencrypt(row.stored(), row.context());
          if (jdbc.update(update, fresh, row.id(), row.stored()) > 0) { // NOSONAR java:S2077
            rewritten++;
            metrics.increment("clavaris.crypto.reencrypted", "store", target.store());
          }
        } catch (final IllegalStateException _) {
          unreadable++;
          metrics.increment("clavaris.crypto.reencrypt_unreadable", "store", target.store());
          LOG.warn(
              "event=secret_reencrypt_unreadable store={} table={} rowId={}",
              target.store(),
              target.table(),
              row.id());
        }
      }
    } while (batch.size() == batchSize);
    if (rewritten > 0 || unreadable > 0) {
      LOG.info(
          "event=secrets_reencrypted store={} table={} column={} rewritten={} unreadable={}",
          target.store(),
          target.table(),
          target.column(),
          rewritten,
          unreadable);
    }
    return new Result(rewritten, unreadable);
  }

  private record Row(UUID id, String stored, String context) {}
}
