package com.clavaris.app.infrastructure.config;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.ObjectMapper;

/**
 * Optional, additive {@code Idempotency-Key} support for {@code /api/v1/admin/**}'s own mutating
 * (POST) endpoints — same mechanism Stripe/GitHub's own management APIs establish: a client that
 * sends this header on a POST it might need to safely retry (a timeout, a dropped connection — the
 * client genuinely doesn't know whether the original attempt was processed) gets the *original*
 * response replayed verbatim on a retry under the same key, instead of a second, genuinely
 * duplicate {@code Organization}/{@code WebhookEndpoint}/{@code OrganizationClient} created for
 * what was really one logical request. A request with no header is entirely unaffected — this is
 * opt-in per request, never a new requirement an existing caller must adopt to keep working.
 *
 * <p><b>Scoped to POST only</b> — the one HTTP method with no idempotent semantics by definition.
 * Every {@code PUT}/{@code DELETE} on this surface is already naturally idempotent by construction
 * (a {@code PUT} sets a value to the same end state on retry; a {@code DELETE} ends in the same
 * "gone" state either way, modulo the 404-vs-204 status difference REST already accepts), so
 * wrapping them here would add real complexity for no correctness gain.
 *
 * <p><b>Scoped by the authenticated {@code client_id}, not the raw header value alone</b> — two
 * different {@code PlatformClient}s could otherwise collide on the same self-chosen key (e.g. both
 * independently choosing {@code "retry-1"}), silently replaying one client's response to another's
 * genuinely different request. Reads {@link RateLimitIdentifiers#authenticatedPlatformClientId} —
 * this filter is wired (see {@code AdminApiSecurityConfig}) after {@code
 * BearerTokenAuthenticationFilter}/{@code AntiAbuseRateLimitingFilter}, so the caller is already
 * authenticated and rate-limit-checked by the time this filter ever runs.
 *
 * <p><b>Never caches a 5xx response</b> — a server-side failure is not a settled outcome the way a
 * 2xx success or a 4xx client-side rejection is; caching one would permanently trap a legitimate
 * retry behind a transient failure that a fresh attempt might well succeed at. {@link
 * IdempotencyKeyStore#release} clears the claim instead, so the very next retry under the same key
 * is treated as a brand-new attempt.
 *
 * <p><b>A key reused with a genuinely different request body is treated as caller misuse, not
 * replayed</b> — see {@link IdempotencyKeyStore#claim}'s own {@link IdempotencyOutcome#CONFLICT}
 * outcome. Silently replaying the first request's response to a second, different one would be a
 * far worse failure mode than a clear 422 telling the caller their own key reuse was wrong.
 */
class IdempotencyKeyFilter extends OncePerRequestFilter {

  private static final Logger LOG = LoggerFactory.getLogger(IdempotencyKeyFilter.class);

  @SuppressWarnings("PMD.LongVariable")
  private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

  // Deduplicated once here (PMD.AvoidDuplicateLiterals) — every branch of the outcome switch
  // below emits the same metric name with a different "outcome" tag value.
  @SuppressWarnings("PMD.LongVariable")
  private static final String IDEMPOTENCY_DECISION_METRIC = "clavaris.idempotency.decision";

  private static final String OUTCOME_TAG = "outcome";

  private final IdempotencyKeyStore store;
  private final IdempotencyKeyHasher keyHasher;
  private final ObjectMapper objectMapper;
  private final SecurityMetricsRecorder metrics;

  /* package */ IdempotencyKeyFilter(
      final IdempotencyKeyStore store,
      final IdempotencyKeyHasher keyHasher,
      final ObjectMapper objectMapper,
      final SecurityMetricsRecorder metrics) {
    super();
    this.store = store;
    this.keyHasher = keyHasher;
    this.objectMapper = objectMapper;
    this.metrics = metrics;
  }

  // PMD.OnlyOneReturn: five genuinely distinct outcomes (no header/not POST passthrough, no
  // authenticated client passthrough, in-progress, conflict, replay, claimed-then-proceed), each
  // with its own exit — same "each outcome needs its own exit" rationale as
  // RecordAccountLoginDeviceService's own identical suppression elsewhere in this codebase.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Override
  protected void doFilterInternal(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final FilterChain filterChain)
      throws ServletException, IOException {
    final String rawIdempotencyKey = request.getHeader(IDEMPOTENCY_KEY_HEADER);
    if (rawIdempotencyKey == null
        || rawIdempotencyKey.isBlank()
        || !HttpMethod.POST.matches(request.getMethod())) {
      filterChain.doFilter(request, response);
      return;
    }
    final String clientId = RateLimitIdentifiers.authenticatedPlatformClientId(request);
    if (clientId == null) {
      // Should not happen given this filter's own wiring (anchored after authentication) — same
      // defensive "malformed/unexpected state degrades to pass-through" convention
      // AntiAbuseRateLimitingFilter's own rules already follow for a similarly-unreachable-in-
      // practice case.
      filterChain.doFilter(request, response);
      return;
    }

    final CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);
    final String requestBodyHash = sha256Hex(cachedRequest.bodyBytes());
    final String redisKey = "idempotency:" + keyHasher.hash(clientId + ":" + rawIdempotencyKey);

    final IdempotencyClaimResult claimResult = store.claim(redisKey, requestBodyHash);
    switch (claimResult.outcome()) {
      case IN_PROGRESS -> {
        metrics.increment(IDEMPOTENCY_DECISION_METRIC, OUTCOME_TAG, "in_progress");
        writeErrorResponse(
            response,
            HttpStatus.CONFLICT,
            "idempotency_key_in_progress",
            "A request with this Idempotency-Key is already being processed.");
      }
      case CONFLICT -> {
        metrics.increment(IDEMPOTENCY_DECISION_METRIC, OUTCOME_TAG, "conflict");
        writeErrorResponse(
            response,
            HttpStatus.UNPROCESSABLE_ENTITY,
            "idempotency_key_conflict",
            "This Idempotency-Key was already used with a different request body.");
      }
      case REPLAY -> {
        metrics.increment(IDEMPOTENCY_DECISION_METRIC, OUTCOME_TAG, "replay");
        replay(response, claimResult.cachedResponse());
      }
      case CLAIMED -> {
        metrics.increment(IDEMPOTENCY_DECISION_METRIC, OUTCOME_TAG, "claimed");
        proceedAndRecordOutcome(cachedRequest, response, filterChain, redisKey, requestBodyHash);
      }
    }
  }

  private void proceedAndRecordOutcome(
      final CachedBodyHttpServletRequest cachedRequest,
      final HttpServletResponse response,
      final FilterChain filterChain,
      final String redisKey,
      final String requestBodyHash)
      throws ServletException, IOException {
    final ContentCachingResponseWrapper wrappedResponse =
        new ContentCachingResponseWrapper(response);
    try {
      filterChain.doFilter(cachedRequest, wrappedResponse);
    } finally {
      final int status = wrappedResponse.getStatus();
      if (status < HttpStatus.INTERNAL_SERVER_ERROR.value()) {
        store.complete(
            redisKey,
            requestBodyHash,
            new IdempotentResponse(
                status, wrappedResponse.getContentType(), wrappedResponse.getContentAsByteArray()));
      } else {
        // Never cache a 5xx — see this class's own Javadoc.
        store.release(redisKey);
      }
      // Mandatory with ContentCachingResponseWrapper — without this, the buffered body never
      // actually reaches the real client, a well-documented Spring gotcha, not an oversight here.
      wrappedResponse.copyBodyToResponse();
    }
  }

  // PMD.LawOfDemeter: response.getOutputStream() is the standard Servlet API shape for writing a
  // body directly from a filter — same rationale AntiAbuseRateLimitingFilter's own identical
  // suppression documents for response.getWriter().
  @SuppressWarnings("PMD.LawOfDemeter")
  private void replay(final HttpServletResponse response, final IdempotentResponse cached)
      throws IOException {
    response.setStatus(cached.status());
    if (cached.contentType() != null) {
      response.setContentType(cached.contentType());
    }
    if (cached.body() != null) {
      response.getOutputStream().write(cached.body());
    }
  }

  @SuppressWarnings("PMD.LawOfDemeter") // same rationale as replay's own identical suppression.
  private void writeErrorResponse(
      final HttpServletResponse response,
      final HttpStatus status,
      final String error,
      final String message)
      throws IOException {
    final String correlationId = UUID.randomUUID().toString();
    LOG.info("event=idempotency_key_rejected error={} correlationId={}", error, correlationId);
    response.setStatus(status.value());
    response.setContentType("application/json");
    response
        .getOutputStream()
        .write(
            objectMapper.writeValueAsBytes(
                new IdempotencyErrorResponse(error, message, correlationId, Instant.now())));
  }

  // NoSuchAlgorithmException is unreachable in practice — SHA-256 is a JCA algorithm every
  // conforming JVM ships, same rationale HmacSha256Hasher's own identical catch documents.
  private static String sha256Hex(final byte[] body) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("Unable to compute SHA-256", e);
    }
  }

  /** Same shape as {@code GlobalExceptionHandler}'s own {@code ErrorResponse}, plus a message. */
  private record IdempotencyErrorResponse(
      String error, String message, String correlationId, Instant timestamp) {}
}
