package com.clavaris.app.infrastructure.config;

import java.time.Duration;

/**
 * @param allowed whether this attempt is under the limit and may proceed
 * @param currentCount the count in the current window, including this attempt — always incremented,
 *     even when {@code allowed} is {@code false}, so a caller logging a rejection can report how
 *     far over the limit the request was
 * @param retryAfter how long until the current window resets — the value a {@code Retry-After}
 *     response header should carry on a {@code 429}
 */
record RateLimitDecision(boolean allowed, long currentCount, Duration retryAfter) {}
