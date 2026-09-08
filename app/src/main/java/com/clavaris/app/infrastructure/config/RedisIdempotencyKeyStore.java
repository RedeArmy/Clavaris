package com.clavaris.app.infrastructure.config;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR-0004 (Redis for exactly this kind of cross-request, short-lived coordination state) — the
 * Redis-backed {@link IdempotencyKeyStore}, same "one Lua script for the atomic step, plain
 * commands for the rest" shape {@link RedisFixedWindowRateLimiter} already establishes.
 *
 * <p><b>Why the claim step needs a script, not a plain {@code SET NX}:</b> a plain {@code SET key
 * value NX} tells the caller only whether it won the race, never what the losing value already was
 * — and this store needs that value (to distinguish "still in flight" from "already done, here is
 * the response to replay" from "done with a different body, this is a conflict"). {@code GET} then
 * a conditional {@code SET} as two separate round trips would themselves race under concurrent
 * callers; the Lua script below runs both as one atomic step against Redis's own single-threaded
 * command executor, the same atomicity guarantee {@link RedisFixedWindowRateLimiter}'s own Javadoc
 * documents for its own script.
 *
 * <p>Entries are plain JSON ({@link StoredEntry}, via the shared {@link ObjectMapper} bean) — a
 * byte[] body round-trips through Jackson's own default Base64 encoding for {@code byte[]}, so a
 * binary or non-UTF-8 response body still replays byte-for-byte, not just a UTF-8-safe one.
 *
 * <p><b>Fails OPEN on a Redis outage, not closed</b> — same TD-SEC-022 trade-off {@link
 * RedisFixedWindowRateLimiter} already establishes for this exact failure mode: idempotency
 * protection is a safety net against a client's own retry, not a correctness-critical gate, so a
 * transient Redis outage degrades to "no deduplication this one request" rather than making the
 * entire admin API surface unavailable. {@link #complete}/{@link #release} degrade the same way for
 * the opposite reason — by the time either runs, the real mutation this request came here to
 * perform has already happened (or definitively failed); failing to cache/release its outcome must
 * never turn an already-real result into a spurious 500 for the caller.
 */
@Component
class RedisIdempotencyKeyStore implements IdempotencyKeyStore {

  private static final Logger LOG = LoggerFactory.getLogger(RedisIdempotencyKeyStore.class);

  // KEYS[1]: the idempotency key. ARGV[1]: the JSON to set (IN_PROGRESS state) if the key is
  // absent. ARGV[2]: that claim's own TTL in seconds — bounded so a crashed request (one that
  // never reaches #complete/#release at all) doesn't permanently block every future retry under
  // the same key.
  //
  // Returns Lua `false` (a nil bulk reply, deserialized as Java `null`) when this call won the
  // claim; otherwise the existing stored JSON, for the caller to interpret.
  private static final RedisScript<String> CLAIM_SCRIPT =
      new DefaultRedisScript<>(
          """
          local existing = redis.call('GET', KEYS[1])
          if existing == false then
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            return false
          end
          return existing
          """,
          String.class);

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final SecurityMetricsRecorder metrics;
  private final Duration claimTtl;
  private final Duration completedTtl;

  @SuppressWarnings("java:S107") // one parameter per collaborator/tuning value — same rationale as
  // every other multi-collaborator constructor in this codebase.
  /* package */ RedisIdempotencyKeyStore(
      final StringRedisTemplate redisTemplate,
      final ObjectMapper objectMapper,
      final SecurityMetricsRecorder metrics,
      @Value("${clavaris.idempotency.claim-ttl-seconds:30}") final long claimTtlSeconds,
      @SuppressWarnings("PMD.LongVariable")
          @Value("${clavaris.idempotency.completed-ttl-seconds:86400}")
          final long completedTtlSeconds) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
    this.metrics = metrics;
    this.claimTtl = Duration.ofSeconds(claimTtlSeconds);
    this.completedTtl = Duration.ofSeconds(completedTtlSeconds);
  }

  // PMD.GuardLogStatement false positive — every logged argument is a cheap in-memory accessor,
  // same rationale as every other logging call site in this codebase (e.g.
  // RedisFixedWindowRateLimiter's own identical suppression). PMD.OnlyOneReturn: four genuinely
  // distinct outcomes (fail-open, claimed, in-progress, replay, conflict), each with its own exit
  // — same "each outcome needs its own exit" rationale as IdempotencyKeyFilter's own identical
  // suppression.
  @SuppressWarnings({"PMD.GuardLogStatement", "PMD.OnlyOneReturn"})
  @Override
  public IdempotencyClaimResult claim(final String key, final String requestBodyHash) {
    final String claimPayload =
        objectMapper.writeValueAsString(StoredEntry.inProgress(requestBodyHash));
    final String existing;
    try {
      existing =
          redisTemplate.execute(
              CLAIM_SCRIPT, List.of(key), claimPayload, String.valueOf(claimTtl.toSeconds()));
    } catch (final DataAccessException e) {
      // TD-SEC-022-shaped fail-open — see this class's own Javadoc.
      LOG.error("event=idempotency_claim_fail_open reason={}", e.getClass().getSimpleName());
      metrics.increment("clavaris.idempotency.fail_open", "reason", e.getClass().getSimpleName());
      return IdempotencyClaimResult.claimed();
    }

    if (existing == null) {
      return IdempotencyClaimResult.claimed();
    }
    final StoredEntry entry = objectMapper.readValue(existing, StoredEntry.class);
    if (entry.inProgress()) {
      return IdempotencyClaimResult.inProgress();
    }
    if (entry.requestBodyHash().equals(requestBodyHash)) {
      return IdempotencyClaimResult.replay(
          new IdempotentResponse(entry.status(), entry.contentType(), entry.body()));
    }
    return IdempotencyClaimResult.conflict();
  }

  @SuppressWarnings("PMD.GuardLogStatement")
  @Override
  public void complete(
      final String key, final String requestBodyHash, final IdempotentResponse response) {
    try {
      redisTemplate
          .opsForValue()
          .set(
              key,
              objectMapper.writeValueAsString(StoredEntry.done(requestBodyHash, response)),
              completedTtl);
    } catch (final DataAccessException e) {
      // See this class's own Javadoc — the real mutation already succeeded; a failure to cache its
      // outcome must never surface as an error to a caller who already got a real, correct
      // response.
      LOG.error("event=idempotency_complete_failed reason={}", e.getClass().getSimpleName());
    }
  }

  @SuppressWarnings("PMD.GuardLogStatement")
  @Override
  public void release(final String key) {
    try {
      redisTemplate.delete(key);
    } catch (final DataAccessException e) {
      // A stuck claim here only means a retry under the same key sees IN_PROGRESS/CONFLICT until
      // its own claimTtl lapses on its own — degraded, not broken, so this is logged, not rethrown.
      LOG.error("event=idempotency_release_failed reason={}", e.getClass().getSimpleName());
    }
  }

  /**
   * The JSON envelope actually stored in Redis — {@code status}/{@code contentType}/{@code body}
   * are {@code null} while {@link #inProgress()}, populated once {@link #done}.
   *
   * <p>{@link #equals}/{@link #hashCode}/{@link #toString} overridden explicitly — same "a record's
   * own generated versions compare/print an array field by reference, not content" reasoning {@link
   * IdempotentResponse}'s own identical override documents, for {@link #body}, the one array
   * component here.
   */
  private record StoredEntry(
      boolean inProgress, String requestBodyHash, Integer status, String contentType, byte[] body) {

    private static StoredEntry inProgress(final String requestBodyHash) {
      return new StoredEntry(true, requestBodyHash, null, null, null);
    }

    private static StoredEntry done(
        final String requestBodyHash, final IdempotentResponse response) {
      return new StoredEntry(
          false, requestBodyHash, response.status(), response.contentType(), response.body());
    }

    // PMD.LongVariable: thatRequestBodyHash names exactly what it is — same "deliberate,
    // descriptive name over an arbitrary shortening" convention this codebase applies everywhere
    // else this rule fires (e.g. RedisFixedWindowRateLimiter's own identical suppression).
    @SuppressWarnings("PMD.LongVariable")
    @Override
    public boolean equals(final Object other) {
      if (this == other) {
        return true;
      }
      if (!(other
          instanceof
          StoredEntry(
              boolean thatInProgress,
              String thatRequestBodyHash,
              Integer thatStatus,
              String thatContentType,
              byte[] thatBody))) {
        return false;
      }
      return inProgress == thatInProgress
          && Objects.equals(requestBodyHash, thatRequestBodyHash)
          && Objects.equals(status, thatStatus)
          && Objects.equals(contentType, thatContentType)
          && Arrays.equals(body, thatBody);
    }

    @Override
    public int hashCode() {
      return Objects.hash(inProgress, requestBodyHash, status, contentType, Arrays.hashCode(body));
    }

    @Override
    public String toString() {
      return "StoredEntry[inProgress="
          + inProgress
          + ", requestBodyHash="
          + requestBodyHash
          + ", status="
          + status
          + ", contentType="
          + contentType
          + ", body="
          + Arrays.toString(body)
          + ']';
    }
  }
}
