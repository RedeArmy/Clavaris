package com.clavaris.common.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KeysetCursorTest {

  @Test
  void roundTripsThroughEncodeAndDecode() {
    final Instant createdAt = Instant.parse("2026-09-14T10:15:30.123456Z");
    final UUID id = UUID.randomUUID();
    final KeysetCursor cursor = new KeysetCursor(createdAt, id);

    final KeysetCursor decoded = KeysetCursor.decode(cursor.encode());

    assertThat(decoded).isEqualTo(cursor);
    assertThat(decoded.createdAt()).isEqualTo(createdAt);
    assertThat(decoded.id()).isEqualTo(id);
  }

  @Test
  void preservesMicrosecondPrecisionAcrossTheRoundTrip() {
    // Postgres timestamptz stores microsecond precision — a cursor truncated to millis could
    // silently skip/repeat a row sharing a millisecond with its neighbor.
    final Instant createdAt = Instant.parse("2026-09-14T10:15:30.123456Z");

    final KeysetCursor decoded =
        KeysetCursor.decode(new KeysetCursor(createdAt, UUID.randomUUID()).encode());

    assertThat(decoded.createdAt().getNano()).isEqualTo(createdAt.getNano());
  }

  @Test
  void encodeProducesAUrlSafeToken() {
    final String token = new KeysetCursor(Instant.now(), UUID.randomUUID()).encode();

    assertThat(token).doesNotContain("+", "/", "=");
  }

  @Test
  void decodeRejectsAMalformedToken() {
    assertThatThrownBy(() -> KeysetCursor.decode("not-a-real-cursor"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
