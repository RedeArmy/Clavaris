package com.clavaris.app.support;

import com.clavaris.app.infrastructure.config.SessionJsonSerializerFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * TD-TEST-011: reads every stored session back with the serializer the application uses, and says
 * which attribute of which session cannot be read, with the reason.
 *
 * <p>A session attribute the serializer refuses (a {@code Long} inside a map or a list is the case
 * that has been seen: it is written with its type, and the type is not on the allow-list) is a
 * server error in whichever request next reads that session, wherever it is. A test that only sees
 * the 500 learns nothing about the attribute; this names it. Only names and reasons are reported,
 * never values: a session holds tokens.
 */
public final class SessionReadability {

  private static final String SESSION_KEYS = "clavaris:sessions:sessions:*";
  private static final String EXPIRY_MARKER = ":expires:";

  private SessionReadability() {}

  /** One line per unreadable attribute: {@code <session key> <field>: <reason>}. */
  public static List<String> unreadableAttributes(final RedisConnectionFactory connectionFactory) {
    final RedisSerializer<Object> serializer =
        SessionJsonSerializerFactory.create(Thread.currentThread().getContextClassLoader());
    final List<String> unreadable = new ArrayList<>();
    try (RedisConnection connection = connectionFactory.getConnection();
        Cursor<byte[]> keys =
            connection.keyCommands().scan(ScanOptions.scanOptions().match(SESSION_KEYS).build())) {
      while (keys.hasNext()) {
        final byte[] key = keys.next();
        final String keyName = new String(key, StandardCharsets.UTF_8);
        if (keyName.contains(EXPIRY_MARKER)) {
          continue;
        }
        final Map<byte[], byte[]> fields = connection.hashCommands().hGetAll(key);
        for (final Map.Entry<byte[], byte[]> field : fields.entrySet()) {
          try {
            serializer.deserialize(field.getValue());
          } catch (final RuntimeException e) {
            unreadable.add(
                keyName
                    + " "
                    + new String(field.getKey(), StandardCharsets.UTF_8)
                    + ": "
                    + firstLine(e));
          }
        }
      }
    }
    return unreadable;
  }

  private static String firstLine(final Throwable e) {
    final String message = String.valueOf(e.getMessage());
    final int newline = message.indexOf('\n');
    return newline < 0 ? message : message.substring(0, newline);
  }
}
