package com.clavaris.app.infrastructure.config;

/**
 * A completed response {@link IdempotencyKeyStore} has cached under an {@code Idempotency-Key} —
 * everything {@link IdempotencyKeyFilter} needs to replay it byte-for-byte on a retry, without
 * re-running the original mutation.
 *
 * @param status the original HTTP status code
 * @param contentType the original {@code Content-Type} header value, possibly {@code null} (a
 *     response body-less status, e.g. 204, may carry none)
 * @param body the original response body, raw bytes — never re-encoded/re-serialized, so a replay
 *     is exactly what the original caller would have received
 */
record IdempotentResponse(int status, String contentType, byte[] body) {}
