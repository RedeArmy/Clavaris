package com.clavaris.app.infrastructure.config;

import java.util.Arrays;
import java.util.Objects;

/**
 * A completed response {@link IdempotencyKeyStore} has cached under an {@code Idempotency-Key} —
 * everything {@link IdempotencyKeyFilter} needs to replay it byte-for-byte on a retry, without
 * re-running the original mutation.
 *
 * <p>{@link #equals}/{@link #hashCode}/{@link #toString} are overridden explicitly — a record's own
 * generated versions compare/print an array field by reference identity, not content, which would
 * make two structurally-identical responses (e.g. two replays of the same cached entry) compare
 * unequal; {@link Arrays#equals(byte[], byte[])}/{@link Arrays#hashCode(byte[])} fix that for
 * {@link #body}, the one array component here.
 *
 * @param status the original HTTP status code
 * @param contentType the original {@code Content-Type} header value, possibly {@code null} (a
 *     response body-less status, e.g. 204, may carry none)
 * @param body the original response body, raw bytes — never re-encoded/re-serialized, so a replay
 *     is exactly what the original caller would have received
 */
record IdempotentResponse(int status, String contentType, byte[] body) {

  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    }
    if (!(other
        instanceof IdempotentResponse(int thatStatus, String thatContentType, byte[] thatBody))) {
      return false;
    }
    return status == thatStatus
        && Objects.equals(contentType, thatContentType)
        && Arrays.equals(body, thatBody);
  }

  @Override
  public int hashCode() {
    return Objects.hash(status, contentType, Arrays.hashCode(body));
  }

  @Override
  public String toString() {
    return "IdempotentResponse[status="
        + status
        + ", contentType="
        + contentType
        + ", body="
        + Arrays.toString(body)
        + ']';
  }
}
